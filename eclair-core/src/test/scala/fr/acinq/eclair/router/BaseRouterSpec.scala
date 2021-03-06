package fr.acinq.eclair.router

import akka.actor.ActorRef
import akka.testkit.TestProbe
import fr.acinq.bitcoin.Crypto.PrivateKey
import fr.acinq.bitcoin.Script.{pay2wsh, write}
import fr.acinq.bitcoin.{Satoshi, Transaction, TxOut}
import fr.acinq.eclair.TestConstants.Alice
import fr.acinq.eclair.blockchain.{IndividualResult, ParallelGetRequest, ParallelGetResponse, WatchSpentBasic}
import fr.acinq.eclair.router.Announcements._
import fr.acinq.eclair.transactions.Scripts
import fr.acinq.eclair.wire._
import fr.acinq.eclair.{TestkitBaseClass, randomKey, _}
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import scala.concurrent.duration._

/**
  * Base class for router testing.
  * It is re-used in payment FSM tests
  * Created by PM on 29/08/2016.
  */
@RunWith(classOf[JUnitRunner])
abstract class BaseRouterSpec extends TestkitBaseClass {

  type FixtureParam = Tuple2[ActorRef, TestProbe]

  val (priv_a, priv_b, priv_c, priv_d, priv_e, priv_f) = (randomKey, randomKey, randomKey, randomKey, randomKey, randomKey)
  val (a, b, c, d, e, f) = (priv_a.publicKey, priv_b.publicKey, priv_c.publicKey, priv_d.publicKey, priv_e.publicKey, priv_f.publicKey)

  val (priv_funding_a, priv_funding_b, priv_funding_c, priv_funding_d, priv_funding_e, priv_funding_f) = (randomKey, randomKey, randomKey, randomKey, randomKey, randomKey)
  val (funding_a, funding_b, funding_c, funding_d, funding_e, funding_f) = (priv_funding_a.publicKey, priv_funding_b.publicKey, priv_funding_c.publicKey, priv_funding_d.publicKey, priv_funding_e.publicKey, priv_funding_f.publicKey)

  //val DUMMY_SIG = BinaryData("3045022100e0a180fdd0fe38037cc878c03832861b40a29d32bd7b40b10c9e1efc8c1468a002205ae06d1624896d0d29f4b31e32772ea3cb1b4d7ed4e077e5da28dcc33c0e781201")

  val ann_a = makeNodeAnnouncement(priv_a, "node-A", (15, 10, -70), Nil)
  val ann_b = makeNodeAnnouncement(priv_b, "node-B", (50, 99, -80), Nil)
  val ann_c = makeNodeAnnouncement(priv_c, "node-C", (123, 100, -40), Nil)
  val ann_d = makeNodeAnnouncement(priv_d, "node-D", (-120, -20, 60), Nil)
  val ann_e = makeNodeAnnouncement(priv_e, "node-E", (-50, 0, 10), Nil)
  val ann_f = makeNodeAnnouncement(priv_f, "node-F", (30, 10, -50), Nil)

  val channelId_ab = toShortId(420000, 1, 0)
  val channelId_bc = toShortId(420000, 2, 0)
  val channelId_cd = toShortId(420000, 3, 0)
  val channelId_ef = toShortId(420000, 4, 0)

  def channelAnnouncement(channelId: Long, node1_priv: PrivateKey, node2_priv: PrivateKey, funding1_priv: PrivateKey, funding2_priv: PrivateKey) = {
    val (node1_sig, funding1_sig) = signChannelAnnouncement(channelId, node1_priv, node2_priv.publicKey, funding1_priv, funding2_priv.publicKey, "")
    val (node2_sig, funding2_sig) = signChannelAnnouncement(channelId, node2_priv, node1_priv.publicKey, funding2_priv, funding1_priv.publicKey, "")
    makeChannelAnnouncement(channelId, node1_priv.publicKey, node2_priv.publicKey, funding1_priv.publicKey, funding2_priv.publicKey, node1_sig, node2_sig, funding1_sig, funding2_sig)
  }

  val chan_ab = channelAnnouncement(channelId_ab, priv_a, priv_b, priv_funding_a, priv_funding_b)
  val chan_bc = channelAnnouncement(channelId_bc, priv_b, priv_c, priv_funding_b, priv_funding_c)
  val chan_cd = channelAnnouncement(channelId_cd, priv_c, priv_d, priv_funding_c, priv_funding_d)
  val chan_ef = channelAnnouncement(channelId_ef, priv_e, priv_f, priv_funding_e, priv_funding_f)

  val channelUpdate_ab = makeChannelUpdate(priv_a, b, channelId_ab, cltvExpiryDelta = 7, 0, feeBaseMsat = 766000, feeProportionalMillionths = 10)
  val channelUpdate_bc = makeChannelUpdate(priv_b, c, channelId_bc, cltvExpiryDelta = 5, 0, feeBaseMsat = 233000, feeProportionalMillionths = 1)
  val channelUpdate_cd = makeChannelUpdate(priv_c, d, channelId_cd, cltvExpiryDelta = 3, 0, feeBaseMsat = 153000, feeProportionalMillionths = 4)
  val channelUpdate_ef = makeChannelUpdate(priv_e, f, channelId_ef, cltvExpiryDelta = 9, 0, feeBaseMsat = 786000, feeProportionalMillionths = 8)

  override def withFixture(test: OneArgTest) = {
    // the network will be a --(1)--> b ---(2)--> c --(3)--> d and e --(4)--> f (we are a)

    within(30 seconds) {

      // first we make sure that we correctly resolve channelId+direction to nodeId
      assert(Router.getDesc(channelUpdate_ab, chan_ab) === ChannelDesc(chan_ab.shortChannelId, priv_a.publicKey, priv_b.publicKey))
      assert(Router.getDesc(channelUpdate_bc, chan_bc) === ChannelDesc(chan_bc.shortChannelId, priv_b.publicKey, priv_c.publicKey))
      assert(Router.getDesc(channelUpdate_cd, chan_cd) === ChannelDesc(chan_cd.shortChannelId, priv_c.publicKey, priv_d.publicKey))
      assert(Router.getDesc(channelUpdate_ef, chan_ef) === ChannelDesc(chan_ef.shortChannelId, priv_e.publicKey, priv_f.publicKey))


      // let's we set up the router
      val watcher = TestProbe()
      val router = system.actorOf(Router.props(Alice.nodeParams, watcher.ref))
      // we announce channels
      router ! chan_ab
      router ! chan_bc
      router ! chan_cd
      router ! chan_ef
      router ! 'tick_validate // we manually trigger a validation
      // watcher receives the get tx requests
      watcher.expectMsg(ParallelGetRequest(chan_ab :: chan_bc :: chan_cd :: chan_ef :: Nil))
      // and answers with valid scripts
      watcher.send(router, ParallelGetResponse(
        IndividualResult(chan_ab, Some(Transaction(version = 0, txIn = Nil, txOut = TxOut(Satoshi(1000000), write(pay2wsh(Scripts.multiSig2of2(funding_a, funding_b)))) :: Nil, lockTime = 0)), true) ::
        IndividualResult(chan_bc, Some(Transaction(version = 0, txIn = Nil, txOut = TxOut(Satoshi(1000000), write(pay2wsh(Scripts.multiSig2of2(funding_b, funding_c)))) :: Nil, lockTime = 0)), true) ::
        IndividualResult(chan_cd, Some(Transaction(version = 0, txIn = Nil, txOut = TxOut(Satoshi(1000000), write(pay2wsh(Scripts.multiSig2of2(funding_c, funding_d)))) :: Nil, lockTime = 0)), true) ::
        IndividualResult(chan_ef, Some(Transaction(version = 0, txIn = Nil, txOut = TxOut(Satoshi(1000000), write(pay2wsh(Scripts.multiSig2of2(funding_e, funding_f)))) :: Nil, lockTime = 0)), true) :: Nil
      ))
      // watcher receives watch-spent request
      watcher.expectMsgType[WatchSpentBasic]
      watcher.expectMsgType[WatchSpentBasic]
      watcher.expectMsgType[WatchSpentBasic]
      watcher.expectMsgType[WatchSpentBasic]
      // then nodes
      router ! ann_a
      router ! ann_b
      router ! ann_c
      router ! ann_d
      router ! ann_e
      router ! ann_f
      // then channel updates
      router ! channelUpdate_ab
      router ! channelUpdate_bc
      router ! channelUpdate_cd
      router ! channelUpdate_ef

      val sender = TestProbe()

      sender.send(router, 'nodes)
      val nodes = sender.expectMsgType[Iterable[NodeAnnouncement]]
      assert(nodes.size === 6)

      sender.send(router, 'channels)
      val channels = sender.expectMsgType[Iterable[ChannelAnnouncement]]
      assert(channels.size === 4)

      sender.send(router, 'updates)
      val updates = sender.expectMsgType[Iterable[ChannelUpdate]]
      assert(updates.size === 4)

      test((router, watcher))
    }
  }

}
