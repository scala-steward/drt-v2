package queueus

import manifests.queues.FastTrackFromCSV
import uk.gov.homeoffice.drt.models.ManifestLike
import uk.gov.homeoffice.drt.ports.PaxTypes.{NonVisaNational, VisaNational}
import uk.gov.homeoffice.drt.ports.Queues.Queue
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.ports.{PaxType, Queues}


trait QueueAllocator {
  def queueRatios: Map[Terminal, Map[PaxType, Seq[(Queue, Double)]]]

  def forTerminalAndManifest(terminal: Terminal, manifest: ManifestLike)(paxType: PaxType): Seq[(Queue, Double)]

  def queueRatio(terminal: Terminal, paxType: PaxType): Seq[(Queue, Double)] =
    queueRatios.getOrElse(terminal, Map()).getOrElse(paxType, Seq())
}

case class TerminalQueueAllocator(queueRatios: Map[Terminal, Map[PaxType, Seq[(Queue, Double)]]]) extends QueueAllocator {
  override def forTerminalAndManifest(terminal: Terminal, manifest: ManifestLike)(paxType: PaxType): Seq[(Queue, Double)] =
    queueRatio(terminal, paxType)
}

case class TerminalQueueAllocatorWithFastTrack(queueRatios: Map[Terminal, Map[PaxType, Seq[(Queue, Double)]]]) extends QueueAllocator {
  override def forTerminalAndManifest(terminal: Terminal, manifest: ManifestLike)(paxType: PaxType): Seq[(Queue, Double)] =
    if (paxType == NonVisaNational || paxType == VisaNational)
      FastTrackFromCSV.fastTrackCarriers
        .find(ftc => ftc.iataCode == manifest.carrierCode || ftc.icaoCode == manifest.carrierCode)
        .map(fts => {
          Seq((Queues.FastTrack, fts.fastTrackSplit), (Queues.NonEeaDesk, 1.0 - fts.fastTrackSplit))
        })
        .getOrElse(queueRatio(terminal, paxType))
    else queueRatio(terminal, paxType)
}
