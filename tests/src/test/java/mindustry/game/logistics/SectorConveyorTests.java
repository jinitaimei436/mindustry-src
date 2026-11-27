package mindustry.game.logistics;

import mindustry.core.*;
import mindustry.game.logistics.SectorConveyorMath.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure-Java JUnit 5 unit tests for V2.8 Global Resource View & Cross-Sector Conveyor system:
 * version tag, 3x3 & 4x4 directional middle-tile geometry, combined max-4 senders per sector,
 * unlimited receivers, lowest-available auto-numbering & reuse, strict 1-to-1 adjacent-only linking,
 * cross-tier rate capping, multi-item FIFO lossless backpressure, port removal auto-disconnect,
 * and binary serialization roundtrip.
 */
public class SectorConveyorTests{

    @Test
    void versionForkTagIsV32(){
        assertEquals("v3.9", Version.forkVersion);
    }

    @Test
    void directionalMiddlePortFaceTilesMatch3x3And4x4Geometry(){
        // 3x3 Junior: occupied [-1, 0, +1] -> middle 1 tile on back (input) & front (output)
        int[][] jFrontRight = SectorConveyorMath.computePortFaceTiles(10, 20, 3, 0, true);
        assertEquals(1, jFrontRight.length);
        assertArrayEquals(new int[]{12, 20}, jFrontRight[0]);

        int[][] jBackRight = SectorConveyorMath.computePortFaceTiles(10, 20, 3, 0, false);
        assertEquals(1, jBackRight.length);
        assertArrayEquals(new int[]{8, 20}, jBackRight[0]);

        int[][] jFrontUp = SectorConveyorMath.computePortFaceTiles(10, 20, 3, 1, true);
        assertArrayEquals(new int[]{10, 22}, jFrontUp[0]);
        int[][] jBackUp = SectorConveyorMath.computePortFaceTiles(10, 20, 3, 1, false);
        assertArrayEquals(new int[]{10, 18}, jBackUp[0]);

        // 4x4 Senior: occupied [-1, 0, +1, +2] -> middle 2 tiles (offsets 0 and +1)
        int[][] sFrontRight = SectorConveyorMath.computePortFaceTiles(10, 20, 4, 0, true);
        assertEquals(2, sFrontRight.length);
        assertArrayEquals(new int[]{13, 20}, sFrontRight[0]);
        assertArrayEquals(new int[]{13, 21}, sFrontRight[1]);

        int[][] sBackRight = SectorConveyorMath.computePortFaceTiles(10, 20, 4, 0, false);
        assertEquals(2, sBackRight.length);
        assertArrayEquals(new int[]{8, 20}, sBackRight[0]);
        assertArrayEquals(new int[]{8, 21}, sBackRight[1]);

        int[][] sFrontDown = SectorConveyorMath.computePortFaceTiles(10, 20, 4, 3, true);
        assertEquals(2, sFrontDown.length);
        assertArrayEquals(new int[]{10, 18}, sFrontDown[0]);
        assertArrayEquals(new int[]{11, 18}, sFrontDown[1]);

        int[][] sBackDown = SectorConveyorMath.computePortFaceTiles(10, 20, 4, 3, false);
        assertEquals(2, sBackDown.length);
        assertArrayEquals(new int[]{10, 23}, sBackDown[0]);
        assertArrayEquals(new int[]{11, 23}, sBackDown[1]);
    }

    @Test
    void enforcesCombinedMax4SendersAndUnlimitedReceiversWithAutoNumbering(){
        PlanetConveyorTopology topo = new PlanetConveyorTopology();

        // Allocate & register 4 mixed Junior + Senior senders in sector 5
        for(int i = 1; i <= 4; i++){
            int num = topo.allocatePortNumber(5, true);
            assertEquals(i, num);
            int tier = (i % 2 == 0) ? SectorConveyorMath.TIER_SENIOR : SectorConveyorMath.TIER_JUNIOR;
            assertNotNull(topo.registerPort(5, num, true, tier));
        }

        // 5th sender rejected regardless of tier
        assertEquals(-1, topo.allocatePortNumber(5, true));
        assertNull(topo.registerPort(5, 5, true, SectorConveyorMath.TIER_JUNIOR));
        assertEquals(4, topo.countSendersInSector(5));

        // Removing sender #2 frees slot #2 for immediate reuse
        assertTrue(topo.unregisterPort(5, 2, true));
        assertEquals(2, topo.allocatePortNumber(5, true));
        assertNotNull(topo.registerPort(5, 2, true, SectorConveyorMath.TIER_SENIOR));

        // Receivers have no cap (register 7 receivers smoothly)
        for(int i = 1; i <= 7; i++){
            int num = topo.allocatePortNumber(5, false);
            assertEquals(i, num);
            assertNotNull(topo.registerPort(5, num, false, SectorConveyorMath.TIER_JUNIOR));
        }
        assertEquals(7, topo.countReceiversInSector(5));
    }

    @Test
    void strictOneToOneAdjacentOnlyLinkingAndCrossTierSpeedCap(){
        PlanetConveyorTopology topo = new PlanetConveyorTopology();
        // Sector 1: Senior Sender #1 (4/s) & Junior Sender #2 (2/s)
        topo.registerPort(1, 1, true, SectorConveyorMath.TIER_SENIOR);
        topo.registerPort(1, 2, true, SectorConveyorMath.TIER_JUNIOR);
        // Sector 2 (adjacent): Junior Receiver #1 (2/s) & Senior Receiver #2 (4/s)
        topo.registerPort(2, 1, false, SectorConveyorMath.TIER_JUNIOR);
        topo.registerPort(2, 2, false, SectorConveyorMath.TIER_SENIOR);
        // Sector 3 (non-adjacent): Senior Receiver #1
        topo.registerPort(3, 1, false, SectorConveyorMath.TIER_SENIOR);

        // Non-adjacent rejected
        assertFalse(topo.bind(1, 1, 3, 1, false));
        // Same sector rejected
        topo.registerPort(1, 1, false, SectorConveyorMath.TIER_SENIOR);
        assertFalse(topo.bind(1, 1, 1, 1, true));

        // Mixed-tier Senior Sender #1 -> Junior Receiver #1 succeeds, capped at 2/s
        assertTrue(topo.bind(1, 1, 2, 1, true));
        SectorConveyorLink mixedLink = topo.getLinkForSender(1, 1);
        assertNotNull(mixedLink);
        assertEquals(2f, topo.effectiveLinkRate(mixedLink), 1e-4f);

        // Strict 1-to-1: cannot bind another sender to already-bound receiver (2, 1)
        assertFalse(topo.bind(1, 2, 2, 1, true));
        // Strict 1-to-1: cannot bind already-bound sender (1, 1) to another receiver (2, 2)
        assertFalse(topo.bind(1, 1, 2, 2, true));

        // Destroying receiver (2, 1) auto-severs link and frees sender (1, 1)
        assertTrue(topo.unregisterPort(2, 1, false));
        assertNull(topo.getLinkForSender(1, 1));
        assertTrue(topo.bind(1, 1, 2, 2, true));
        assertEquals(4f, topo.effectiveLinkRate(topo.getLinkForSender(1, 1)), 1e-4f);
    }

    @Test
    void multiItemFifoPreservesOrderAndAppliesLosslessBackpressureWhenFull(){
        PlanetConveyorTopology topo = new PlanetConveyorTopology();
        // Senior Sender (cap 40, 4/s) linked to Junior Receiver (cap 20, 2/s) -> effective rate = 2/s
        SectorPortRecord sender = topo.registerPort(10, 1, true, SectorConveyorMath.TIER_SENIOR);
        SectorPortRecord receiver = topo.registerPort(11, 1, false, SectorConveyorMath.TIER_JUNIOR);
        assertTrue(topo.bind(10, 1, 11, 1, true));

        // Enqueue 25 mixed items (IDs 1, 2, 3, 1, 2, 3...) into sender
        for(int i = 0; i < 25; i++){
            assertTrue(sender.enqueue((i % 3) + 1));
        }
        assertEquals(25, sender.bufferedCount());

        // Step 1 second -> cross-tier rate = 2/s moves exactly 2 items in FIFO order (1, 2)
        int moved1 = topo.stepSenderTransfer(10, 1, 1.0f, 1.0f);
        assertEquals(2, moved1);
        assertEquals(23, sender.bufferedCount());
        assertEquals(2, receiver.bufferedCount());
        assertEquals(1, receiver.dequeue());
        assertEquals(2, receiver.dequeue());

        // Now fill receiver completely to 20/20 via 10 seconds of transfer (2/s * 10s = 20 items)
        for(int sec = 0; sec < 12; sec++){
            topo.stepSenderTransfer(10, 1, 1.0f, 1.0f);
        }

        // Receiver must be capped at exactly 20, sender retains remaining 3 items (zero loss!), backpressure active
        assertEquals(20, receiver.bufferedCount());
        assertEquals(3, sender.bufferedCount());
        assertTrue(sender.backpressurePaused);

        // Drain 2 items from receiver -> next 1s step resumes and moves 2 items cleanly
        assertTrue(receiver.dequeue() >= 1);
        assertTrue(receiver.dequeue() >= 1);
        int resumed = topo.stepSenderTransfer(10, 1, 1.0f, 1.0f);
        assertEquals(2, resumed);
        assertEquals(1, sender.bufferedCount());
        assertEquals(20, receiver.bufferedCount());
    }

    @Test
    void binarySerializationRoundtripPreservesPortsFifoLinksAndRates() throws IOException{
        PlanetConveyorTopology original = new PlanetConveyorTopology();
        SectorPortRecord s1 = original.registerPort(4, 1, true, SectorConveyorMath.TIER_SENIOR);
        SectorPortRecord r1 = original.registerPort(5, 1, false, SectorConveyorMath.TIER_SENIOR);
        assertTrue(original.bind(4, 1, 5, 1, true));

        s1.enqueue(0);
        s1.enqueue(1);
        s1.enqueue(2);
        s1.enqueue(3);
        s1.enqueue(4);
        // Step 1 second at 4/s -> 4 items move to receiver, 1 stays in sender, rate window completes
        assertEquals(4, original.stepSenderTransfer(4, 1, 1.0f, 1.0f));
        r1.advanceRateWindow(1.0f);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try(DataOutputStream out = new DataOutputStream(baos)){
            original.writeToStream(out);
        }

        PlanetConveyorTopology restored = new PlanetConveyorTopology();
        try(DataInputStream in = new DataInputStream(new ByteArrayInputStream(baos.toByteArray()))){
            restored.readFromStream(in);
        }

        SectorPortRecord rs1 = restored.getPort(4, true, 1);
        SectorPortRecord rr1 = restored.getPort(5, false, 1);
        assertNotNull(rs1);
        assertNotNull(rr1);
        assertEquals(SectorConveyorMath.TIER_SENIOR, rs1.tier);
        assertEquals(1, rs1.bufferedCount());
        assertEquals(4, rs1.peek());
        assertEquals(4, rr1.bufferedCount());
        assertEquals(0, rr1.dequeue());
        assertEquals(1, rr1.dequeue());
        assertEquals(2, rr1.dequeue());
        assertEquals(3, rr1.dequeue());

        SectorConveyorLink link = restored.getLinkForSender(4, 1);
        assertNotNull(link);
        assertEquals(5, link.receiverSectorId);
        assertEquals(1, link.receiverPortNumber);
        assertEquals(4f, rs1.recentTotalRate, 1e-3f);
    }
}
