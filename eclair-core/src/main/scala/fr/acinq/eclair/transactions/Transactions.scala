/*
 * Copyright 2019 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.acinq.eclair.transactions

import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey, ripemd160}
import fr.acinq.bitcoin.Script._
import fr.acinq.bitcoin.SigVersion._
import fr.acinq.bitcoin._
import fr.acinq.eclair._
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.transactions.CommitmentOutput._
import fr.acinq.eclair.transactions.Scripts._
import fr.acinq.eclair.wire.protocol.UpdateAddHtlc
import scodec.bits.ByteVector

import java.nio.ByteOrder
import scala.reflect.runtime.universe._
import scala.util.Try

/**
 * Created by PM on 15/12/2016.
 */
object Transactions {

  sealed trait CommitmentFormat {
    // @formatter:off
    def commitWeight: Int
    def htlcOutputWeight: Int
    def htlcTimeoutWeight: Int
    def htlcSuccessWeight: Int
    // @formatter:on
  }

  /**
   * Commitment format as defined in the v1.0 specification (https://github.com/lightningnetwork/lightning-rfc/tree/v1.0).
   */
  case object DefaultCommitmentFormat extends CommitmentFormat {
    override val commitWeight = 724
    override val htlcOutputWeight = 172
    override val htlcTimeoutWeight = 663
    override val htlcSuccessWeight = 703
  }

  /**
   * Commitment format that adds anchor outputs to the commitment transaction and uses custom sighash flags for HTLC
   * transactions to allow unilateral fee bumping (https://github.com/lightningnetwork/lightning-rfc/pull/688).
   */
  sealed trait AnchorOutputsCommitmentFormat extends CommitmentFormat {
    override val commitWeight = 1124
    override val htlcOutputWeight = 172
    override val htlcTimeoutWeight = 666
    override val htlcSuccessWeight = 706
  }

  object AnchorOutputsCommitmentFormat {
    val anchorAmount = 330 sat
  }

  /**
   * This commitment format may be unsafe where you're fundee, as it exposes you to a fee inflating attack.
   * Don't use this commitment format unless you know what you're doing!
   * See https://lists.linuxfoundation.org/pipermail/lightning-dev/2020-September/002796.html for details.
   */
  case object UnsafeLegacyAnchorOutputsCommitmentFormat extends AnchorOutputsCommitmentFormat

  /**
   * This commitment format removes the fees from the pre-signed 2nd-stage htlc transactions to fix the fee inflating
   * attack against [[UnsafeLegacyAnchorOutputsCommitmentFormat]].
   */
  case object ZeroFeeHtlcTxAnchorOutputsCommitmentFormat extends AnchorOutputsCommitmentFormat

  // @formatter:off
  case class OutputInfo(index: Long, amount: Satoshi, publicKeyScript: ByteVector)
  case class InputInfo(outPoint: OutPoint, txOut: TxOut, redeemScript: ByteVector)
  object InputInfo {
    def apply(outPoint: OutPoint, txOut: TxOut, redeemScript: Seq[ScriptElt]) = new InputInfo(outPoint, txOut, Script.write(redeemScript))
  }

  /** Owner of a given transaction (local/remote). */
  sealed trait TxOwner
  object TxOwner {
    case object Local extends TxOwner
    case object Remote extends TxOwner
  }

  sealed trait TransactionWithInputInfo {
    def input: InputInfo
    def tx: Transaction
    def amountIn: Satoshi = input.txOut.amount
    def fee: Satoshi = amountIn - tx.txOut.map(_.amount).sum
    def minRelayFee: Satoshi = {
      val vsize = (tx.weight() + 3) / 4
      Satoshi(FeeratePerKw.MinimumRelayFeeRate * vsize / 1000)
    }
    /** Sighash flags to use when signing the transaction. */
    def sighash(txOwner: TxOwner, commitmentFormat: CommitmentFormat): Int = SIGHASH_ALL
  }
  sealed trait ReplaceableTransactionWithInputInfo extends TransactionWithInputInfo {
    /** Block before which the transaction must be confirmed. */
    def confirmBefore: BlockHeight
  }

  case class CommitTx(input: InputInfo, tx: Transaction) extends TransactionWithInputInfo
  /**
   * It's important to note that htlc transactions with the default commitment format are not actually replaceable: only
   * anchor outputs htlc transactions are replaceable. We should have used different types for these different kinds of
   * htlc transactions, but we introduced that before implementing the replacement strategy.
   * Unfortunately, if we wanted to change that, we would have to update the codecs and implement a migration of channel
   * data, which isn't trivial, so we chose to temporarily live with that inconsistency (and have the transaction
   * replacement logic abort when non-anchor outputs htlc transactions are provided).
   * Ideally, we'd like to implement a dynamic commitment format upgrade mechanism and depreciate the pre-anchor outputs
   * format soon, which will get rid of this inconsistency.
   * The next time we introduce a new type of commitment, we should avoid repeating that mistake and define separate
   * types right from the start.
   */
  sealed trait HtlcTx extends ReplaceableTransactionWithInputInfo {
    def htlcId: Long
    override def sighash(txOwner: TxOwner, commitmentFormat: CommitmentFormat): Int = commitmentFormat match {
      case DefaultCommitmentFormat => SIGHASH_ALL
      case _: AnchorOutputsCommitmentFormat => txOwner match {
        case TxOwner.Local => SIGHASH_ALL
        case TxOwner.Remote => SIGHASH_SINGLE | SIGHASH_ANYONECANPAY
      }
    }
  }
  case class HtlcSuccessTx(input: InputInfo, tx: Transaction, paymentHash: ByteVector32, htlcId: Long, confirmBefore: BlockHeight) extends HtlcTx
  case class HtlcTimeoutTx(input: InputInfo, tx: Transaction, htlcId: Long, confirmBefore: BlockHeight) extends HtlcTx
  case class HtlcDelayedTx(input: InputInfo, tx: Transaction) extends TransactionWithInputInfo
  sealed trait ClaimHtlcTx extends ReplaceableTransactionWithInputInfo { def htlcId: Long }
  case class LegacyClaimHtlcSuccessTx(input: InputInfo, tx: Transaction, htlcId: Long, confirmBefore: BlockHeight) extends ClaimHtlcTx
  case class ClaimHtlcSuccessTx(input: InputInfo, tx: Transaction, paymentHash: ByteVector32, htlcId: Long, confirmBefore: BlockHeight) extends ClaimHtlcTx
  case class ClaimHtlcTimeoutTx(input: InputInfo, tx: Transaction, htlcId: Long, confirmBefore: BlockHeight) extends ClaimHtlcTx
  sealed trait ClaimAnchorOutputTx extends TransactionWithInputInfo
  case class ClaimLocalAnchorOutputTx(input: InputInfo, tx: Transaction, confirmBefore: BlockHeight) extends ClaimAnchorOutputTx with ReplaceableTransactionWithInputInfo
  case class ClaimRemoteAnchorOutputTx(input: InputInfo, tx: Transaction) extends ClaimAnchorOutputTx
  sealed trait ClaimRemoteCommitMainOutputTx extends TransactionWithInputInfo
  case class ClaimP2WPKHOutputTx(input: InputInfo, tx: Transaction) extends ClaimRemoteCommitMainOutputTx
  case class ClaimRemoteDelayedOutputTx(input: InputInfo, tx: Transaction) extends ClaimRemoteCommitMainOutputTx
  case class ClaimLocalDelayedOutputTx(input: InputInfo, tx: Transaction) extends TransactionWithInputInfo
  case class MainPenaltyTx(input: InputInfo, tx: Transaction) extends TransactionWithInputInfo
  case class HtlcPenaltyTx(input: InputInfo, tx: Transaction) extends TransactionWithInputInfo
  case class ClaimHtlcDelayedOutputPenaltyTx(input: InputInfo, tx: Transaction) extends TransactionWithInputInfo
  case class ClosingTx(input: InputInfo, tx: Transaction, toLocalOutput: Option[OutputInfo]) extends TransactionWithInputInfo

  sealed trait TxGenerationResult[+T <: TransactionWithInputInfo] {
    def map[U <: TransactionWithInputInfo: TypeTag](transform: T => U): TxGenerationResult[U] = this match {
      case TxGenerationResult.Success(txInfo) => TxGenerationResult.Success(transform(txInfo))
      case skipped: TxGenerationResult.Skipped => skipped
      case failure: TxGenerationResult.Failure => failure
    }

    def toOption: Option[T] = this match {
      case TxGenerationResult.Success(txInfo) => Some(txInfo)
      case _ => None
    }

    def get: T = toOption.get
  }
  object TxGenerationResult {
    case class Success[T <: TransactionWithInputInfo: TypeTag](txInfo: T) extends TxGenerationResult[T]
    sealed trait Skipped extends TxGenerationResult[Nothing]
    case object OutputNotFound extends Skipped { override def toString = "output not found (probably trimmed)" }
    case object AmountBelowDustLimit extends Skipped { override def toString = "amount is below dust limit" }
    case class Failure(reason: String) extends TxGenerationResult[Nothing] { override def toString = s"unknown failure (reason=$reason)" }

    /** Used for backward compatibility. Not defining a type saves us from having to maintain compatibility in new codecs. */
    val BackWardCompatFailure: Failure = Failure("placeholder for backward compatibility")
  }

  // @formatter:on

  /**
   * When *local* *current* [[CommitTx]] is published:
   *   - [[ClaimLocalDelayedOutputTx]] spends to-local output of [[CommitTx]] after a delay
   *   - When using anchor outputs, [[ClaimLocalAnchorOutputTx]] spends to-local anchor of [[CommitTx]]
   *   - [[HtlcSuccessTx]] spends htlc-received outputs of [[CommitTx]] for which we have the preimage
   *     - [[HtlcDelayedTx]] spends [[HtlcSuccessTx]] after a delay
   *   - [[HtlcTimeoutTx]] spends htlc-sent outputs of [[CommitTx]] after a timeout
   *     - [[HtlcDelayedTx]] spends [[HtlcTimeoutTx]] after a delay
   *
   * When *remote* *current* [[CommitTx]] is published:
   *   - When using the default commitment format, [[ClaimP2WPKHOutputTx]] spends to-local output of [[CommitTx]]
   *   - When using anchor outputs, [[ClaimRemoteDelayedOutputTx]] spends to-local output of [[CommitTx]]
   *   - When using anchor outputs, [[ClaimLocalAnchorOutputTx]] spends to-local anchor of [[CommitTx]]
   *   - [[ClaimHtlcSuccessTx]] spends htlc-received outputs of [[CommitTx]] for which we have the preimage
   *   - [[ClaimHtlcTimeoutTx]] spends htlc-sent outputs of [[CommitTx]] after a timeout
   *
   * When *remote* *revoked* [[CommitTx]] is published:
   *   - When using the default commitment format, [[ClaimP2WPKHOutputTx]] spends to-local output of [[CommitTx]]
   *   - When using anchor outputs, [[ClaimRemoteDelayedOutputTx]] spends to-local output of [[CommitTx]]
   *   - When using anchor outputs, [[ClaimLocalAnchorOutputTx]] spends to-local anchor of [[CommitTx]]
   *   - [[MainPenaltyTx]] spends remote main output using the per-commitment secret
   *   - [[HtlcSuccessTx]] spends htlc-sent outputs of [[CommitTx]] for which they have the preimage (published by remote)
   *     - [[ClaimHtlcDelayedOutputPenaltyTx]] spends [[HtlcSuccessTx]] using the revocation secret (published by local)
   *   - [[HtlcTimeoutTx]] spends htlc-received outputs of [[CommitTx]] after a timeout (published by remote)
   *     - [[ClaimHtlcDelayedOutputPenaltyTx]] spends [[HtlcTimeoutTx]] using the revocation secret (published by local)
   *   - [[HtlcPenaltyTx]] spends competes with [[HtlcSuccessTx]] and [[HtlcTimeoutTx]] for the same outputs (published by local)
   */

  /**
   * these values are specific to us (not defined in the specification) and used to estimate fees
   */
  val claimP2WPKHOutputWitnessWeight = 109
  val claimP2WPKHOutputWeight = 438
  // The smallest transaction that spends an anchor contains 2 inputs (the commit tx output and a wallet input to set the feerate)
  // and 1 output (change). If we're using P2WPKH wallet inputs/outputs with 72 bytes signatures, this results in a weight of 717.
  // We round it down to 700 to allow for some error margin (e.g. signatures smaller than 72 bytes).
  val claimAnchorOutputMinWeight = 700
  // The biggest htlc input is an HTLC-success with anchor outputs:
  // 143 bytes (accepted_htlc_script) + 327 bytes (success_witness) + 41 bytes (commitment_input) = 511 bytes
  // See https://github.com/lightningnetwork/lightning-rfc/blob/master/03-transactions.md#expected-weight-of-htlc-timeout-and-htlc-success-transactions
  val htlcInputMaxWeight = 511
  val htlcDelayedWeight = 483
  val claimHtlcSuccessWeight = 571
  val claimHtlcTimeoutWeight = 545
  val mainPenaltyWeight = 484
  val htlcPenaltyWeight = 578 // based on spending an HTLC-Success output (would be 571 with HTLC-Timeout)

  def weight2feeMsat(feeratePerKw: FeeratePerKw, weight: Int): MilliSatoshi = MilliSatoshi(feeratePerKw.toLong * weight)

  def weight2fee(feeratePerKw: FeeratePerKw, weight: Int): Satoshi = weight2feeMsat(feeratePerKw, weight).truncateToSatoshi

  /**
   * @param fee    tx fee
   * @param weight tx weight
   * @return the fee rate (in Satoshi/Kw) for this tx
   */
  def fee2rate(fee: Satoshi, weight: Int): FeeratePerKw = FeeratePerKw((fee * 1000L) / weight)

  /** Offered HTLCs below this amount will be trimmed. */
  def offeredHtlcTrimThreshold(dustLimit: Satoshi, spec: CommitmentSpec, commitmentFormat: CommitmentFormat): Satoshi =
    dustLimit + weight2fee(spec.htlcTxFeerate(commitmentFormat), commitmentFormat.htlcTimeoutWeight)

  def offeredHtlcTrimThreshold(dustLimit: Satoshi, feerate: FeeratePerKw, commitmentFormat: CommitmentFormat): Satoshi = {
    commitmentFormat match {
      case ZeroFeeHtlcTxAnchorOutputsCommitmentFormat => dustLimit
      case _ => dustLimit + weight2fee(feerate, commitmentFormat.htlcTimeoutWeight)
    }
  }

  def trimOfferedHtlcs(dustLimit: Satoshi, spec: CommitmentSpec, commitmentFormat: CommitmentFormat): Seq[OutgoingHtlc] = {
    val threshold = offeredHtlcTrimThreshold(dustLimit, spec, commitmentFormat)
    spec.htlcs
      .collect { case o: OutgoingHtlc if o.add.amountMsat >= threshold => o }
      .toSeq
  }

  /** Received HTLCs below this amount will be trimmed. */
  def receivedHtlcTrimThreshold(dustLimit: Satoshi, spec: CommitmentSpec, commitmentFormat: CommitmentFormat): Satoshi =
    dustLimit + weight2fee(spec.htlcTxFeerate(commitmentFormat), commitmentFormat.htlcSuccessWeight)

  def receivedHtlcTrimThreshold(dustLimit: Satoshi, feerate: FeeratePerKw, commitmentFormat: CommitmentFormat): Satoshi = {
    commitmentFormat match {
      case ZeroFeeHtlcTxAnchorOutputsCommitmentFormat => dustLimit
      case _ => dustLimit + weight2fee(feerate, commitmentFormat.htlcSuccessWeight)
    }
  }

  def trimReceivedHtlcs(dustLimit: Satoshi, spec: CommitmentSpec, commitmentFormat: CommitmentFormat): Seq[IncomingHtlc] = {
    val threshold = receivedHtlcTrimThreshold(dustLimit, spec, commitmentFormat)
    spec.htlcs
      .collect { case i: IncomingHtlc if i.add.amountMsat >= threshold => i }
      .toSeq
  }

  /** Fee for an un-trimmed HTLC. */
  def htlcOutputFee(feeratePerKw: FeeratePerKw, commitmentFormat: CommitmentFormat): MilliSatoshi = weight2feeMsat(feeratePerKw, commitmentFormat.htlcOutputWeight)

  /** Fee paid by the commit tx (depends on which HTLCs will be trimmed). */
  def commitTxFeeMsat(dustLimit: Satoshi, spec: CommitmentSpec, commitmentFormat: CommitmentFormat): MilliSatoshi = {
    val trimmedOfferedHtlcs = trimOfferedHtlcs(dustLimit, spec, commitmentFormat)
    val trimmedReceivedHtlcs = trimReceivedHtlcs(dustLimit, spec, commitmentFormat)
    val weight = commitmentFormat.commitWeight + commitmentFormat.htlcOutputWeight * (trimmedOfferedHtlcs.size + trimmedReceivedHtlcs.size)
    weight2feeMsat(spec.commitTxFeerate, weight)
  }

  /**
   * While on-chain amounts are generally computed in Satoshis (since this is the smallest on-chain unit), it may be
   * useful in some cases to calculate it in MilliSatoshi to avoid rounding issues.
   * If you are adding multiple fees together for example, you should always add them in MilliSatoshi and then round
   * down to Satoshi.
   */
  def commitTxTotalCostMsat(dustLimit: Satoshi, spec: CommitmentSpec, commitmentFormat: CommitmentFormat): MilliSatoshi = {
    // The funder pays the on-chain fee by deducing it from its main output.
    val txFee = commitTxFeeMsat(dustLimit, spec, commitmentFormat)
    // When using anchor outputs, the funder pays for *both* anchors all the time, even if only one anchor is present.
    // This is not technically a fee (it doesn't go to miners) but it also has to be deduced from the funder's main output.
    val anchorsCost = commitmentFormat match {
      case DefaultCommitmentFormat => Satoshi(0)
      case _: AnchorOutputsCommitmentFormat => AnchorOutputsCommitmentFormat.anchorAmount * 2
    }
    txFee + anchorsCost
  }

  def commitTxTotalCost(dustLimit: Satoshi, spec: CommitmentSpec, commitmentFormat: CommitmentFormat): Satoshi = commitTxTotalCostMsat(dustLimit, spec, commitmentFormat).truncateToSatoshi

  /**
   * @param commitTxNumber         commit tx number
   * @param isFunder               true if local node is funder
   * @param localPaymentBasePoint  local payment base point
   * @param remotePaymentBasePoint remote payment base point
   * @return the obscured tx number as defined in BOLT #3 (a 48 bits integer)
   */
  def obscuredCommitTxNumber(commitTxNumber: Long, isFunder: Boolean, localPaymentBasePoint: PublicKey, remotePaymentBasePoint: PublicKey): Long = {
    // from BOLT 3: SHA256(payment-basepoint from open_channel || payment-basepoint from accept_channel)
    val h = if (isFunder) {
      Crypto.sha256(localPaymentBasePoint.value ++ remotePaymentBasePoint.value)
    } else {
      Crypto.sha256(remotePaymentBasePoint.value ++ localPaymentBasePoint.value)
    }
    val blind = Protocol.uint64((h.takeRight(6).reverse ++ ByteVector.fromValidHex("0000")).toArray, ByteOrder.LITTLE_ENDIAN)
    commitTxNumber ^ blind
  }

  /**
   * @param commitTx               commit tx
   * @param isFunder               true if local node is funder
   * @param localPaymentBasePoint  local payment base point
   * @param remotePaymentBasePoint remote payment base point
   * @return the actual commit tx number that was blinded and stored in locktime and sequence fields
   */
  def getCommitTxNumber(commitTx: Transaction, isFunder: Boolean, localPaymentBasePoint: PublicKey, remotePaymentBasePoint: PublicKey): Long = {
    require(commitTx.txIn.size == 1, "commitment tx should have 1 input")
    val blind = obscuredCommitTxNumber(0, isFunder, localPaymentBasePoint, remotePaymentBasePoint)
    val obscured = decodeTxNumber(commitTx.txIn.head.sequence, commitTx.lockTime)
    obscured ^ blind
  }

  /**
   * This is a trick to split and encode a 48-bit txnumber into the sequence and locktime fields of a tx
   *
   * @param txnumber commitment number
   * @return (sequence, locktime)
   */
  def encodeTxNumber(txnumber: Long): (Long, Long) = {
    require(txnumber <= 0xffffffffffffL, "txnumber must be lesser than 48 bits long")
    (0x80000000L | (txnumber >> 24), (txnumber & 0xffffffL) | 0x20000000)
  }

  def decodeTxNumber(sequence: Long, locktime: Long): Long = ((sequence & 0xffffffL) << 24) + (locktime & 0xffffffL)

  def getHtlcTxInputSequence(commitmentFormat: CommitmentFormat): Long = commitmentFormat match {
    case DefaultCommitmentFormat => 0 // htlc txs immediately spend the commit tx
    case _: AnchorOutputsCommitmentFormat => 1 // htlc txs have a 1-block delay to allow CPFP carve-out on anchors
  }

  /**
   * Represent a link between a commitment spec item (to-local, to-remote, anchors, htlc) and the actual output in the commit tx
   *
   * @param output           transaction output
   * @param redeemScript     redeem script that matches this output (most of them are p2wsh)
   * @param commitmentOutput commitment spec item this output is built from
   */
  case class CommitmentOutputLink[T <: CommitmentOutput](output: TxOut, redeemScript: Seq[ScriptElt], commitmentOutput: T)

  /** Type alias for a collection of commitment output links */
  type CommitmentOutputs = Seq[CommitmentOutputLink[CommitmentOutput]]

  object CommitmentOutputLink {
    /**
     * We sort HTLC outputs according to BIP69 + CLTV as tie-breaker for offered HTLC, we do this only for the outgoing
     * HTLC because we must agree with the remote on the order of HTLC-Timeout transactions even for identical HTLC outputs.
     * See https://github.com/lightningnetwork/lightning-rfc/issues/448#issuecomment-432074187.
     */
    def sort(a: CommitmentOutputLink[CommitmentOutput], b: CommitmentOutputLink[CommitmentOutput]): Boolean = (a.commitmentOutput, b.commitmentOutput) match {
      case (OutHtlc(OutgoingHtlc(htlcA)), OutHtlc(OutgoingHtlc(htlcB))) if htlcA.paymentHash == htlcB.paymentHash && htlcA.amountMsat.truncateToSatoshi == htlcB.amountMsat.truncateToSatoshi =>
        htlcA.cltvExpiry <= htlcB.cltvExpiry
      case _ => LexicographicalOrdering.isLessThan(a.output, b.output)
    }
  }

  def makeCommitTxOutputs(localIsFunder: Boolean,
                          localDustLimit: Satoshi,
                          localRevocationPubkey: PublicKey,
                          toLocalDelay: CltvExpiryDelta,
                          localDelayedPaymentPubkey: PublicKey,
                          remotePaymentPubkey: PublicKey,
                          localHtlcPubkey: PublicKey,
                          remoteHtlcPubkey: PublicKey,
                          localFundingPubkey: PublicKey,
                          remoteFundingPubkey: PublicKey,
                          spec: CommitmentSpec,
                          commitmentFormat: CommitmentFormat): CommitmentOutputs = {
    val outputs = collection.mutable.ArrayBuffer.empty[CommitmentOutputLink[CommitmentOutput]]

    trimOfferedHtlcs(localDustLimit, spec, commitmentFormat).foreach { htlc =>
      val redeemScript = htlcOffered(localHtlcPubkey, remoteHtlcPubkey, localRevocationPubkey, ripemd160(htlc.add.paymentHash.bytes), commitmentFormat)
      outputs.append(CommitmentOutputLink(TxOut(htlc.add.amountMsat.truncateToSatoshi, pay2wsh(redeemScript)), redeemScript, OutHtlc(htlc)))
    }

    trimReceivedHtlcs(localDustLimit, spec, commitmentFormat).foreach { htlc =>
      val redeemScript = htlcReceived(localHtlcPubkey, remoteHtlcPubkey, localRevocationPubkey, ripemd160(htlc.add.paymentHash.bytes), htlc.add.cltvExpiry, commitmentFormat)
      outputs.append(CommitmentOutputLink(TxOut(htlc.add.amountMsat.truncateToSatoshi, pay2wsh(redeemScript)), redeemScript, InHtlc(htlc)))
    }

    val hasHtlcs = outputs.nonEmpty

    val (toLocalAmount: Satoshi, toRemoteAmount: Satoshi) = if (localIsFunder) {
      (spec.toLocal.truncateToSatoshi - commitTxTotalCost(localDustLimit, spec, commitmentFormat), spec.toRemote.truncateToSatoshi)
    } else {
      (spec.toLocal.truncateToSatoshi, spec.toRemote.truncateToSatoshi - commitTxTotalCost(localDustLimit, spec, commitmentFormat))
    } // NB: we don't care if values are < 0, they will be trimmed if they are < dust limit anyway

    if (toLocalAmount >= localDustLimit) {
      outputs.append(CommitmentOutputLink(
        TxOut(toLocalAmount, pay2wsh(toLocalDelayed(localRevocationPubkey, toLocalDelay, localDelayedPaymentPubkey))),
        toLocalDelayed(localRevocationPubkey, toLocalDelay, localDelayedPaymentPubkey),
        ToLocal))
    }

    if (toRemoteAmount >= localDustLimit) {
      commitmentFormat match {
        case DefaultCommitmentFormat => outputs.append(CommitmentOutputLink(
          TxOut(toRemoteAmount, pay2wpkh(remotePaymentPubkey)),
          pay2pkh(remotePaymentPubkey),
          ToRemote))
        case _: AnchorOutputsCommitmentFormat => outputs.append(CommitmentOutputLink(
          TxOut(toRemoteAmount, pay2wsh(toRemoteDelayed(remotePaymentPubkey))),
          toRemoteDelayed(remotePaymentPubkey),
          ToRemote))
      }
    }

    commitmentFormat match {
      case _: AnchorOutputsCommitmentFormat =>
        if (toLocalAmount >= localDustLimit || hasHtlcs) {
          outputs.append(CommitmentOutputLink(TxOut(AnchorOutputsCommitmentFormat.anchorAmount, pay2wsh(anchor(localFundingPubkey))), anchor(localFundingPubkey), ToLocalAnchor))
        }
        if (toRemoteAmount >= localDustLimit || hasHtlcs) {
          outputs.append(CommitmentOutputLink(TxOut(AnchorOutputsCommitmentFormat.anchorAmount, pay2wsh(anchor(remoteFundingPubkey))), anchor(remoteFundingPubkey), ToRemoteAnchor))
        }
      case _ =>
    }

    outputs.sortWith(CommitmentOutputLink.sort).toSeq
  }

  def makeCommitTx(commitTxInput: InputInfo,
                   commitTxNumber: Long,
                   localPaymentBasePoint: PublicKey,
                   remotePaymentBasePoint: PublicKey,
                   localIsFunder: Boolean,
                   outputs: CommitmentOutputs): CommitTx = {
    val txNumber = obscuredCommitTxNumber(commitTxNumber, localIsFunder, localPaymentBasePoint, remotePaymentBasePoint)
    val (sequence, lockTime) = encodeTxNumber(txNumber)

    val tx = Transaction(
      version = 2,
      txIn = TxIn(commitTxInput.outPoint, ByteVector.empty, sequence = sequence) :: Nil,
      txOut = outputs.map(_.output),
      lockTime = lockTime)

    CommitTx(commitTxInput, tx)
  }

  def makeHtlcTimeoutTx(commitTx: Transaction,
                        output: CommitmentOutputLink[OutHtlc],
                        outputIndex: Int,
                        localDustLimit: Satoshi,
                        localRevocationPubkey: PublicKey,
                        toLocalDelay: CltvExpiryDelta,
                        localDelayedPaymentPubkey: PublicKey,
                        feeratePerKw: FeeratePerKw,
                        commitmentFormat: CommitmentFormat): TxGenerationResult[HtlcTimeoutTx] = {
    val fee = weight2fee(feeratePerKw, commitmentFormat.htlcTimeoutWeight)
    val redeemScript = output.redeemScript
    val htlc = output.commitmentOutput.outgoingHtlc.add
    val amount = htlc.amountMsat.truncateToSatoshi - fee
    if (amount < localDustLimit) {
      TxGenerationResult.AmountBelowDustLimit
    } else {
      val input = InputInfo(OutPoint(commitTx, outputIndex), commitTx.txOut(outputIndex), write(redeemScript))
      val tx = Transaction(
        version = 2,
        txIn = TxIn(input.outPoint, ByteVector.empty, getHtlcTxInputSequence(commitmentFormat)) :: Nil,
        txOut = TxOut(amount, pay2wsh(toLocalDelayed(localRevocationPubkey, toLocalDelay, localDelayedPaymentPubkey))) :: Nil,
        lockTime = htlc.cltvExpiry.toLong
      )
      TxGenerationResult.Success(HtlcTimeoutTx(input, tx, htlc.id, BlockHeight(htlc.cltvExpiry.toLong)))
    }
  }

  def makeHtlcSuccessTx(commitTx: Transaction,
                        output: CommitmentOutputLink[InHtlc],
                        outputIndex: Int,
                        localDustLimit: Satoshi,
                        localRevocationPubkey: PublicKey,
                        toLocalDelay: CltvExpiryDelta,
                        localDelayedPaymentPubkey: PublicKey,
                        feeratePerKw: FeeratePerKw,
                        commitmentFormat: CommitmentFormat): TxGenerationResult[HtlcSuccessTx] = {
    val fee = weight2fee(feeratePerKw, commitmentFormat.htlcSuccessWeight)
    val redeemScript = output.redeemScript
    val htlc = output.commitmentOutput.incomingHtlc.add
    val amount = htlc.amountMsat.truncateToSatoshi - fee
    if (amount < localDustLimit) {
      TxGenerationResult.AmountBelowDustLimit
    } else {
      val input = InputInfo(OutPoint(commitTx, outputIndex), commitTx.txOut(outputIndex), write(redeemScript))
      val tx = Transaction(
        version = 2,
        txIn = TxIn(input.outPoint, ByteVector.empty, getHtlcTxInputSequence(commitmentFormat)) :: Nil,
        txOut = TxOut(amount, pay2wsh(toLocalDelayed(localRevocationPubkey, toLocalDelay, localDelayedPaymentPubkey))) :: Nil,
        lockTime = 0
      )
      TxGenerationResult.Success(HtlcSuccessTx(input, tx, htlc.paymentHash, htlc.id, BlockHeight(htlc.cltvExpiry.toLong)))
    }
  }

  def makeHtlcTxs(commitTx: Transaction,
                  localDustLimit: Satoshi,
                  localRevocationPubkey: PublicKey,
                  toLocalDelay: CltvExpiryDelta,
                  localDelayedPaymentPubkey: PublicKey,
                  feeratePerKw: FeeratePerKw,
                  outputs: CommitmentOutputs,
                  commitmentFormat: CommitmentFormat): Seq[HtlcTx] = {
    val htlcTimeoutTxs = outputs.zipWithIndex.collect {
      case (CommitmentOutputLink(o, s, OutHtlc(ou)), outputIndex) =>
        val co = CommitmentOutputLink(o, s, OutHtlc(ou))
        makeHtlcTimeoutTx(commitTx, co, outputIndex, localDustLimit, localRevocationPubkey, toLocalDelay, localDelayedPaymentPubkey, feeratePerKw, commitmentFormat)
    }.collect { case TxGenerationResult.Success(htlcTimeoutTx) => htlcTimeoutTx }
    val htlcSuccessTxs = outputs.zipWithIndex.collect {
      case (CommitmentOutputLink(o, s, InHtlc(in)), outputIndex) =>
        val co = CommitmentOutputLink(o, s, InHtlc(in))
        makeHtlcSuccessTx(commitTx, co, outputIndex, localDustLimit, localRevocationPubkey, toLocalDelay, localDelayedPaymentPubkey, feeratePerKw, commitmentFormat)
    }.collect { case TxGenerationResult.Success(htlcSuccessTx) => htlcSuccessTx }
    htlcTimeoutTxs ++ htlcSuccessTxs
  }

  def resolveOutput(outputs: CommitmentOutputs, outgoingHtlc: OutgoingHtlc): Option[Int] =
    outputs.zipWithIndex.collectFirst { case (CommitmentOutputLink(_, _, OutHtlc(og: OutgoingHtlc)), outIndex) if og.add.id == outgoingHtlc.add.id => outIndex }

  def makeClaimHtlcSuccessTx(commitTx: Transaction,
                             outputs: CommitmentOutputs,
                             localDustLimit: Satoshi,
                             localHtlcPubkey: PublicKey,
                             remoteHtlcPubkey: PublicKey,
                             remoteRevocationPubkey: PublicKey,
                             localFinalScriptPubKey: ByteVector,
                             htlc: UpdateAddHtlc,
                             feeratePerKw: FeeratePerKw,
                             commitmentFormat: CommitmentFormat): TxGenerationResult[ClaimHtlcSuccessTx] = {
    val redeemScript = htlcOffered(remoteHtlcPubkey, localHtlcPubkey, remoteRevocationPubkey, ripemd160(htlc.paymentHash.bytes), commitmentFormat)
    resolveOutput(outputs, OutgoingHtlc(htlc)) match {
      case Some(outputIndex) =>
        val input = InputInfo(OutPoint(commitTx, outputIndex), commitTx.txOut(outputIndex), write(redeemScript))
        // unsigned tx
        val tx = Transaction(
          version = 2,
          txIn = TxIn(input.outPoint, ByteVector.empty, getHtlcTxInputSequence(commitmentFormat)) :: Nil,
          txOut = TxOut(Satoshi(0), localFinalScriptPubKey) :: Nil,
          lockTime = 0)
        val weight = addSigs(ClaimHtlcSuccessTx(input, tx, htlc.paymentHash, htlc.id, BlockHeight(htlc.cltvExpiry.toLong)), PlaceHolderSig, ByteVector32.Zeroes).tx.weight()
        val fee = weight2fee(feeratePerKw, weight)
        val amount = input.txOut.amount - fee
        if (amount < localDustLimit) {
          TxGenerationResult.AmountBelowDustLimit
        } else {
          val tx1 = tx.copy(txOut = tx.txOut.head.copy(amount = amount) :: Nil)
          TxGenerationResult.Success(ClaimHtlcSuccessTx(input, tx1, htlc.paymentHash, htlc.id, BlockHeight(htlc.cltvExpiry.toLong)))
        }
      case None => TxGenerationResult.OutputNotFound
    }
  }

  def resolveOutput(outputs: CommitmentOutputs, incomingHtlc: IncomingHtlc): Option[Int] =
    outputs.zipWithIndex.collectFirst { case (CommitmentOutputLink(_, _, InHtlc(ih)), outIndex) if ih.add.id == incomingHtlc.add.id => outIndex }

  def makeClaimHtlcTimeoutTx(commitTx: Transaction,
                             outputs: CommitmentOutputs,
                             localDustLimit: Satoshi,
                             localHtlcPubkey: PublicKey,
                             remoteHtlcPubkey: PublicKey,
                             remoteRevocationPubkey: PublicKey,
                             localFinalScriptPubKey: ByteVector,
                             htlc: UpdateAddHtlc,
                             feeratePerKw: FeeratePerKw,
                             commitmentFormat: CommitmentFormat): TxGenerationResult[ClaimHtlcTimeoutTx] = {
    val redeemScript = htlcReceived(remoteHtlcPubkey, localHtlcPubkey, remoteRevocationPubkey, ripemd160(htlc.paymentHash.bytes), htlc.cltvExpiry, commitmentFormat)
    resolveOutput(outputs, IncomingHtlc(htlc)) match {
      case Some(outputIndex) =>
        val input = InputInfo(OutPoint(commitTx, outputIndex), commitTx.txOut(outputIndex), write(redeemScript))
        // unsigned tx
        val tx = Transaction(
          version = 2,
          txIn = TxIn(input.outPoint, ByteVector.empty, getHtlcTxInputSequence(commitmentFormat)) :: Nil,
          txOut = TxOut(Satoshi(0), localFinalScriptPubKey) :: Nil,
          lockTime = htlc.cltvExpiry.toLong)
        val weight = addSigs(ClaimHtlcTimeoutTx(input, tx, htlc.id, BlockHeight(htlc.cltvExpiry.toLong)), PlaceHolderSig).tx.weight()
        val fee = weight2fee(feeratePerKw, weight)
        val amount = input.txOut.amount - fee
        if (amount < localDustLimit) {
          TxGenerationResult.AmountBelowDustLimit
        } else {
          val tx1 = tx.copy(txOut = tx.txOut.head.copy(amount = amount) :: Nil)
          TxGenerationResult.Success(ClaimHtlcTimeoutTx(input, tx1, htlc.id, BlockHeight(htlc.cltvExpiry.toLong)))
        }
      case None => TxGenerationResult.OutputNotFound
    }
  }

  def makeClaimP2WPKHOutputTx(commitTx: Transaction, localDustLimit: Satoshi, localPaymentPubkey: PublicKey, localFinalScriptPubKey: ByteVector, feeratePerKw: FeeratePerKw): TxGenerationResult[ClaimP2WPKHOutputTx] = {
    val redeemScript = Script.pay2pkh(localPaymentPubkey)
    val pubkeyScript = write(pay2wpkh(localPaymentPubkey))
    findPubKeyScriptIndex(commitTx, pubkeyScript) match {
      case Left(outputNotFound) => outputNotFound
      case Right(outputIndex) =>
        val input = InputInfo(OutPoint(commitTx, outputIndex), commitTx.txOut(outputIndex), write(redeemScript))
        // unsigned tx
        val tx = Transaction(
          version = 2,
          txIn = TxIn(input.outPoint, ByteVector.empty, 0x00000000L) :: Nil,
          txOut = TxOut(Satoshi(0), localFinalScriptPubKey) :: Nil,
          lockTime = 0)
        // compute weight with a dummy 73 bytes signature (the largest you can get) and a dummy 33 bytes pubkey
        val weight = addSigs(ClaimP2WPKHOutputTx(input, tx), PlaceHolderPubKey, PlaceHolderSig).tx.weight()
        val fee = weight2fee(feeratePerKw, weight)
        val amount = input.txOut.amount - fee
        if (amount < localDustLimit) {
          TxGenerationResult.AmountBelowDustLimit
        } else {
          val tx1 = tx.copy(txOut = tx.txOut.head.copy(amount = amount) :: Nil)
          TxGenerationResult.Success(ClaimP2WPKHOutputTx(input, tx1))
        }
    }
  }

  def makeClaimRemoteDelayedOutputTx(commitTx: Transaction, localDustLimit: Satoshi, localPaymentPubkey: PublicKey, localFinalScriptPubKey: ByteVector, feeratePerKw: FeeratePerKw): TxGenerationResult[ClaimRemoteDelayedOutputTx] = {
    val redeemScript = toRemoteDelayed(localPaymentPubkey)
    val pubkeyScript = write(pay2wsh(redeemScript))
    findPubKeyScriptIndex(commitTx, pubkeyScript) match {
      case Left(outputNotFound) => outputNotFound
      case Right(outputIndex) =>
        val input = InputInfo(OutPoint(commitTx, outputIndex), commitTx.txOut(outputIndex), write(redeemScript))
        // unsigned transaction
        val tx = Transaction(
          version = 2,
          txIn = TxIn(input.outPoint, ByteVector.empty, 1) :: Nil,
          txOut = TxOut(Satoshi(0), localFinalScriptPubKey) :: Nil,
          lockTime = 0)
        // compute weight with a dummy 73 bytes signature (the largest you can get)
        val weight = addSigs(ClaimRemoteDelayedOutputTx(input, tx), PlaceHolderSig).tx.weight()
        val fee = weight2fee(feeratePerKw, weight)
        val amount = input.txOut.amount - fee
        if (amount < localDustLimit) {
          TxGenerationResult.AmountBelowDustLimit
        } else {
          val tx1 = tx.copy(txOut = tx.txOut.head.copy(amount = amount) :: Nil)
          TxGenerationResult.Success(ClaimRemoteDelayedOutputTx(input, tx1))
        }
    }
  }

  def makeHtlcDelayedTx(htlcTx: Transaction, localDustLimit: Satoshi, localRevocationPubkey: PublicKey, toLocalDelay: CltvExpiryDelta, localDelayedPaymentPubkey: PublicKey, localFinalScriptPubKey: ByteVector, feeratePerKw: FeeratePerKw): TxGenerationResult[HtlcDelayedTx] =
    makeLocalDelayedOutputTx(htlcTx, localDustLimit, localRevocationPubkey, toLocalDelay, localDelayedPaymentPubkey, localFinalScriptPubKey, feeratePerKw, HtlcDelayedTx)

  def makeClaimLocalDelayedOutputTx(commitTx: Transaction, localDustLimit: Satoshi, localRevocationPubkey: PublicKey, toLocalDelay: CltvExpiryDelta, localDelayedPaymentPubkey: PublicKey, localFinalScriptPubKey: ByteVector, feeratePerKw: FeeratePerKw): TxGenerationResult[ClaimLocalDelayedOutputTx] =
    makeLocalDelayedOutputTx(commitTx, localDustLimit, localRevocationPubkey, toLocalDelay, localDelayedPaymentPubkey, localFinalScriptPubKey, feeratePerKw, ClaimLocalDelayedOutputTx)

  private def makeLocalDelayedOutputTx[T <: TransactionWithInputInfo : TypeTag](parentTx: Transaction, localDustLimit: Satoshi, localRevocationPubkey: PublicKey, toLocalDelay: CltvExpiryDelta, localDelayedPaymentPubkey: PublicKey, localFinalScriptPubKey: ByteVector, feeratePerKw: FeeratePerKw, transform: (InputInfo, Transaction) => T): TxGenerationResult[T] = {
    val redeemScript = toLocalDelayed(localRevocationPubkey, toLocalDelay, localDelayedPaymentPubkey)
    val pubkeyScript = write(pay2wsh(redeemScript))
    findPubKeyScriptIndex(parentTx, pubkeyScript) match {
      case Left(outputNotFound) => outputNotFound
      case Right(outputIndex) =>
        val input = InputInfo(OutPoint(parentTx, outputIndex), parentTx.txOut(outputIndex), write(redeemScript))
        // unsigned transaction
        val tx = Transaction(
          version = 2,
          txIn = TxIn(input.outPoint, ByteVector.empty, toLocalDelay.toInt) :: Nil,
          txOut = TxOut(Satoshi(0), localFinalScriptPubKey) :: Nil,
          lockTime = 0)
        // compute weight with a dummy 73 bytes signature (the largest you can get)
        val weight = addSigs(ClaimLocalDelayedOutputTx(input, tx), PlaceHolderSig).tx.weight()
        val fee = weight2fee(feeratePerKw, weight)
        val amount = input.txOut.amount - fee
        if (amount < localDustLimit) {
          TxGenerationResult.AmountBelowDustLimit
        } else {
          val tx1 = tx.copy(txOut = tx.txOut.head.copy(amount = amount) :: Nil)
          TxGenerationResult.Success(transform(input, tx1))
        }
    }
  }

  private def makeClaimAnchorOutputTx[T <: TransactionWithInputInfo : TypeTag](commitTx: Transaction, fundingPubkey: PublicKey, transform: (InputInfo, Transaction) => T): TxGenerationResult[T] = {
    val redeemScript = anchor(fundingPubkey)
    val pubkeyScript = write(pay2wsh(redeemScript))
    findPubKeyScriptIndex(commitTx, pubkeyScript) match {
      case Left(outputNotFound) => outputNotFound
      case Right(outputIndex) =>
        val input = InputInfo(OutPoint(commitTx, outputIndex), commitTx.txOut(outputIndex), write(redeemScript))
        // unsigned transaction
        val tx = Transaction(
          version = 2,
          txIn = TxIn(input.outPoint, ByteVector.empty, 0) :: Nil,
          txOut = Nil, // anchor is only used to bump fees, the output will be added later depending on available inputs
          lockTime = 0)
        TxGenerationResult.Success(transform(input, tx))
    }
  }

  def makeClaimLocalAnchorOutputTx(commitTx: Transaction, localFundingPubkey: PublicKey, confirmBefore: BlockHeight): TxGenerationResult[ClaimLocalAnchorOutputTx] =
    makeClaimAnchorOutputTx(commitTx, localFundingPubkey, ClaimLocalAnchorOutputTx(_, _, confirmBefore))

  def makeClaimRemoteAnchorOutputTx(commitTx: Transaction, remoteFundingPubkey: PublicKey): TxGenerationResult[ClaimRemoteAnchorOutputTx] = {
    makeClaimAnchorOutputTx(commitTx, remoteFundingPubkey, ClaimRemoteAnchorOutputTx)
  }

  def makeClaimHtlcDelayedOutputPenaltyTxs(htlcTx: Transaction, localDustLimit: Satoshi, localRevocationPubkey: PublicKey, toLocalDelay: CltvExpiryDelta, localDelayedPaymentPubkey: PublicKey, localFinalScriptPubKey: ByteVector, feeratePerKw: FeeratePerKw): Seq[TxGenerationResult[ClaimHtlcDelayedOutputPenaltyTx]] = {
    val redeemScript = toLocalDelayed(localRevocationPubkey, toLocalDelay, localDelayedPaymentPubkey)
    val pubkeyScript = write(pay2wsh(redeemScript))
    findPubKeyScriptIndexes(htlcTx, pubkeyScript) match {
      case Left(outputNotFound) => Seq(outputNotFound)
      case Right(outputIndexes) => outputIndexes.map(outputIndex => {
        val input = InputInfo(OutPoint(htlcTx, outputIndex), htlcTx.txOut(outputIndex), write(redeemScript))
        // unsigned transaction
        val tx = Transaction(
          version = 2,
          txIn = TxIn(input.outPoint, ByteVector.empty, 0xffffffffL) :: Nil,
          txOut = TxOut(Satoshi(0), localFinalScriptPubKey) :: Nil,
          lockTime = 0)
        // compute weight with a dummy 73 bytes signature (the largest you can get)
        val weight = addSigs(ClaimHtlcDelayedOutputPenaltyTx(input, tx), PlaceHolderSig).tx.weight()
        val fee = weight2fee(feeratePerKw, weight)
        val amount = input.txOut.amount - fee
        if (amount < localDustLimit) {
          TxGenerationResult.AmountBelowDustLimit
        } else {
          val tx1 = tx.copy(txOut = tx.txOut.head.copy(amount = amount) :: Nil)
          TxGenerationResult.Success(ClaimHtlcDelayedOutputPenaltyTx(input, tx1))
        }
      })
    }
  }

  def makeMainPenaltyTx(commitTx: Transaction, localDustLimit: Satoshi, remoteRevocationPubkey: PublicKey, localFinalScriptPubKey: ByteVector, toRemoteDelay: CltvExpiryDelta, remoteDelayedPaymentPubkey: PublicKey, feeratePerKw: FeeratePerKw): TxGenerationResult[MainPenaltyTx] = {
    val redeemScript = toLocalDelayed(remoteRevocationPubkey, toRemoteDelay, remoteDelayedPaymentPubkey)
    val pubkeyScript = write(pay2wsh(redeemScript))
    findPubKeyScriptIndex(commitTx, pubkeyScript) match {
      case Left(outputNotFound) => outputNotFound
      case Right(outputIndex) =>
        val input = InputInfo(OutPoint(commitTx, outputIndex), commitTx.txOut(outputIndex), write(redeemScript))
        // unsigned transaction
        val tx = Transaction(
          version = 2,
          txIn = TxIn(input.outPoint, ByteVector.empty, 0xffffffffL) :: Nil,
          txOut = TxOut(Satoshi(0), localFinalScriptPubKey) :: Nil,
          lockTime = 0)
        // compute weight with a dummy 73 bytes signature (the largest you can get)
        val weight = addSigs(MainPenaltyTx(input, tx), PlaceHolderSig).tx.weight()
        val fee = weight2fee(feeratePerKw, weight)
        val amount = input.txOut.amount - fee
        if (amount < localDustLimit) {
          TxGenerationResult.AmountBelowDustLimit
        } else {
          val tx1 = tx.copy(txOut = tx.txOut.head.copy(amount = amount) :: Nil)
          TxGenerationResult.Success(MainPenaltyTx(input, tx1))
        }
    }
  }

  /**
   * We already have the redeemScript, no need to build it
   */
  def makeHtlcPenaltyTx(commitTx: Transaction, htlcOutputIndex: Int, redeemScript: ByteVector, localDustLimit: Satoshi, localFinalScriptPubKey: ByteVector, feeratePerKw: FeeratePerKw): TxGenerationResult[HtlcPenaltyTx] = {
    val input = InputInfo(OutPoint(commitTx, htlcOutputIndex), commitTx.txOut(htlcOutputIndex), redeemScript)
    // unsigned transaction
    val tx = Transaction(
      version = 2,
      txIn = TxIn(input.outPoint, ByteVector.empty, 0xffffffffL) :: Nil,
      txOut = TxOut(Satoshi(0), localFinalScriptPubKey) :: Nil,
      lockTime = 0)
    // compute weight with a dummy 73 bytes signature (the largest you can get)
    val weight = addSigs(MainPenaltyTx(input, tx), PlaceHolderSig).tx.weight()
    val fee = weight2fee(feeratePerKw, weight)
    val amount = input.txOut.amount - fee
    if (amount < localDustLimit) {
      TxGenerationResult.AmountBelowDustLimit
    } else {
      val tx1 = tx.copy(txOut = tx.txOut.head.copy(amount = amount) :: Nil)
      TxGenerationResult.Success(HtlcPenaltyTx(input, tx1))
    }
  }

  def makeClosingTx(commitTxInput: InputInfo, localScriptPubKey: ByteVector, remoteScriptPubKey: ByteVector, localIsFunder: Boolean, dustLimit: Satoshi, closingFee: Satoshi, spec: CommitmentSpec): ClosingTx = {
    require(spec.htlcs.isEmpty, "there shouldn't be any pending htlcs")

    val (toLocalAmount: Satoshi, toRemoteAmount: Satoshi) = if (localIsFunder) {
      (spec.toLocal.truncateToSatoshi - closingFee, spec.toRemote.truncateToSatoshi)
    } else {
      (spec.toLocal.truncateToSatoshi, spec.toRemote.truncateToSatoshi - closingFee)
    } // NB: we don't care if values are < 0, they will be trimmed if they are < dust limit anyway

    val toLocalOutput_opt = if (toLocalAmount >= dustLimit) Some(TxOut(toLocalAmount, localScriptPubKey)) else None
    val toRemoteOutput_opt = if (toRemoteAmount >= dustLimit) Some(TxOut(toRemoteAmount, remoteScriptPubKey)) else None

    val tx = LexicographicalOrdering.sort(Transaction(
      version = 2,
      txIn = TxIn(commitTxInput.outPoint, ByteVector.empty, sequence = 0xffffffffL) :: Nil,
      txOut = toLocalOutput_opt.toSeq ++ toRemoteOutput_opt.toSeq ++ Nil,
      lockTime = 0))
    val toLocalOutput = findPubKeyScriptIndex(tx, localScriptPubKey).map(index => OutputInfo(index, toLocalAmount, localScriptPubKey)).toOption
    ClosingTx(commitTxInput, tx, toLocalOutput)
  }

  def findPubKeyScriptIndex(tx: Transaction, pubkeyScript: ByteVector): Either[TxGenerationResult.OutputNotFound.type, Int] = {
    val outputIndex = tx.txOut.indexWhere(_.publicKeyScript == pubkeyScript)
    if (outputIndex >= 0) {
      Right(outputIndex)
    } else {
      Left(TxGenerationResult.OutputNotFound)
    }
  }

  def findPubKeyScriptIndexes(tx: Transaction, pubkeyScript: ByteVector): Either[Transactions.TxGenerationResult.OutputNotFound.type, Seq[Int]] = {
    val outputIndexes = tx.txOut.zipWithIndex.collect {
      case (txOut, index) if txOut.publicKeyScript == pubkeyScript => index
    }
    if (outputIndexes.nonEmpty) {
      Right(outputIndexes)
    } else {
      Left(TxGenerationResult.OutputNotFound)
    }
  }

  /**
   * Default public key used for fee estimation
   */
  val PlaceHolderPubKey = PrivateKey(ByteVector32.One).publicKey

  /**
   * This default sig takes 72B when encoded in DER (incl. 1B for the trailing sig hash), it is used for fee estimation
   * It is 72 bytes because our signatures are normalized (low-s) and will take up 72 bytes at most in DER format
   */
  val PlaceHolderSig = ByteVector64(ByteVector.fill(64)(0xaa))
  assert(der(PlaceHolderSig).size == 72)

  private def sign(tx: Transaction, redeemScript: ByteVector, amount: Satoshi, key: PrivateKey, sighashType: Int): ByteVector64 = {
    val sigDER = Transaction.signInput(tx, inputIndex = 0, redeemScript, sighashType, amount, SIGVERSION_WITNESS_V0, key)
    val sig64 = Crypto.der2compact(sigDER)
    sig64
  }

  def sign(txinfo: TransactionWithInputInfo, key: PrivateKey, sighashType: Int): ByteVector64 = {
    // NB: the tx may have multiple inputs, we will only sign the one provided in txinfo.input. Bear in mind that the
    // signature will be invalidated if other inputs are added *afterwards* and sighashType was SIGHASH_ALL.
    sign(txinfo.tx, txinfo.input.redeemScript, txinfo.input.txOut.amount, key, sighashType)
  }

  def sign(txinfo: TransactionWithInputInfo, key: PrivateKey, txOwner: TxOwner, commitmentFormat: CommitmentFormat): ByteVector64 = sign(txinfo, key, txinfo.sighash(txOwner, commitmentFormat))

  def addSigs(commitTx: CommitTx, localFundingPubkey: PublicKey, remoteFundingPubkey: PublicKey, localSig: ByteVector64, remoteSig: ByteVector64): CommitTx = {
    val witness = Scripts.witness2of2(localSig, remoteSig, localFundingPubkey, remoteFundingPubkey)
    commitTx.copy(tx = commitTx.tx.updateWitness(0, witness))
  }

  def addSigs(mainPenaltyTx: MainPenaltyTx, revocationSig: ByteVector64): MainPenaltyTx = {
    val witness = Scripts.witnessToLocalDelayedWithRevocationSig(revocationSig, mainPenaltyTx.input.redeemScript)
    mainPenaltyTx.copy(tx = mainPenaltyTx.tx.updateWitness(0, witness))
  }

  def addSigs(htlcPenaltyTx: HtlcPenaltyTx, revocationSig: ByteVector64, revocationPubkey: PublicKey): HtlcPenaltyTx = {
    val witness = Scripts.witnessHtlcWithRevocationSig(revocationSig, revocationPubkey, htlcPenaltyTx.input.redeemScript)
    htlcPenaltyTx.copy(tx = htlcPenaltyTx.tx.updateWitness(0, witness))
  }

  def addSigs(htlcSuccessTx: HtlcSuccessTx, localSig: ByteVector64, remoteSig: ByteVector64, paymentPreimage: ByteVector32, commitmentFormat: CommitmentFormat): HtlcSuccessTx = {
    val witness = witnessHtlcSuccess(localSig, remoteSig, paymentPreimage, htlcSuccessTx.input.redeemScript, commitmentFormat)
    htlcSuccessTx.copy(tx = htlcSuccessTx.tx.updateWitness(0, witness))
  }

  def addSigs(htlcTimeoutTx: HtlcTimeoutTx, localSig: ByteVector64, remoteSig: ByteVector64, commitmentFormat: CommitmentFormat): HtlcTimeoutTx = {
    val witness = witnessHtlcTimeout(localSig, remoteSig, htlcTimeoutTx.input.redeemScript, commitmentFormat)
    htlcTimeoutTx.copy(tx = htlcTimeoutTx.tx.updateWitness(0, witness))
  }

  def addSigs(claimHtlcSuccessTx: ClaimHtlcSuccessTx, localSig: ByteVector64, paymentPreimage: ByteVector32): ClaimHtlcSuccessTx = {
    val witness = witnessClaimHtlcSuccessFromCommitTx(localSig, paymentPreimage, claimHtlcSuccessTx.input.redeemScript)
    claimHtlcSuccessTx.copy(tx = claimHtlcSuccessTx.tx.updateWitness(0, witness))
  }

  def addSigs(claimHtlcTimeoutTx: ClaimHtlcTimeoutTx, localSig: ByteVector64): ClaimHtlcTimeoutTx = {
    val witness = witnessClaimHtlcTimeoutFromCommitTx(localSig, claimHtlcTimeoutTx.input.redeemScript)
    claimHtlcTimeoutTx.copy(tx = claimHtlcTimeoutTx.tx.updateWitness(0, witness))
  }

  def addSigs(claimP2WPKHOutputTx: ClaimP2WPKHOutputTx, localPaymentPubkey: PublicKey, localSig: ByteVector64): ClaimP2WPKHOutputTx = {
    val witness = ScriptWitness(Seq(der(localSig), localPaymentPubkey.value))
    claimP2WPKHOutputTx.copy(tx = claimP2WPKHOutputTx.tx.updateWitness(0, witness))
  }

  def addSigs(claimRemoteDelayedOutputTx: ClaimRemoteDelayedOutputTx, localSig: ByteVector64): ClaimRemoteDelayedOutputTx = {
    val witness = witnessClaimToRemoteDelayedFromCommitTx(localSig, claimRemoteDelayedOutputTx.input.redeemScript)
    claimRemoteDelayedOutputTx.copy(tx = claimRemoteDelayedOutputTx.tx.updateWitness(0, witness))
  }

  def addSigs(claimDelayedOutputTx: ClaimLocalDelayedOutputTx, localSig: ByteVector64): ClaimLocalDelayedOutputTx = {
    val witness = witnessToLocalDelayedAfterDelay(localSig, claimDelayedOutputTx.input.redeemScript)
    claimDelayedOutputTx.copy(tx = claimDelayedOutputTx.tx.updateWitness(0, witness))
  }

  def addSigs(htlcDelayedTx: HtlcDelayedTx, localSig: ByteVector64): HtlcDelayedTx = {
    val witness = witnessToLocalDelayedAfterDelay(localSig, htlcDelayedTx.input.redeemScript)
    htlcDelayedTx.copy(tx = htlcDelayedTx.tx.updateWitness(0, witness))
  }

  def addSigs(claimAnchorOutputTx: ClaimLocalAnchorOutputTx, localSig: ByteVector64): ClaimLocalAnchorOutputTx = {
    val witness = witnessAnchor(localSig, claimAnchorOutputTx.input.redeemScript)
    claimAnchorOutputTx.copy(tx = claimAnchorOutputTx.tx.updateWitness(0, witness))
  }

  def addSigs(claimHtlcDelayedPenalty: ClaimHtlcDelayedOutputPenaltyTx, revocationSig: ByteVector64): ClaimHtlcDelayedOutputPenaltyTx = {
    val witness = Scripts.witnessToLocalDelayedWithRevocationSig(revocationSig, claimHtlcDelayedPenalty.input.redeemScript)
    claimHtlcDelayedPenalty.copy(tx = claimHtlcDelayedPenalty.tx.updateWitness(0, witness))
  }

  def addSigs(closingTx: ClosingTx, localFundingPubkey: PublicKey, remoteFundingPubkey: PublicKey, localSig: ByteVector64, remoteSig: ByteVector64): ClosingTx = {
    val witness = Scripts.witness2of2(localSig, remoteSig, localFundingPubkey, remoteFundingPubkey)
    closingTx.copy(tx = closingTx.tx.updateWitness(0, witness))
  }

  def checkSpendable(txinfo: TransactionWithInputInfo): Try[Unit] = {
    // NB: we don't verify the other inputs as they should only be wallet inputs used to RBF the transaction
    Try(Transaction.correctlySpends(txinfo.tx, Map(txinfo.input.outPoint -> txinfo.input.txOut), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS))
  }

  def checkSig(txinfo: TransactionWithInputInfo, sig: ByteVector64, pubKey: PublicKey, txOwner: TxOwner, commitmentFormat: CommitmentFormat): Boolean = {
    val sighash = txinfo.sighash(txOwner, commitmentFormat)
    val data = Transaction.hashForSigning(txinfo.tx, inputIndex = 0, txinfo.input.redeemScript, sighash, txinfo.input.txOut.amount, SIGVERSION_WITNESS_V0)
    Crypto.verifySignature(data, sig, pubKey)
  }

}
