/*
 * Copyright © 2020 Peter Doornbosch
 *
 * This file is part of Kwik, a QUIC client Java library
 *
 * Kwik is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version.
 *
 * Kwik is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for
 * more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package net.luminis.quic.send;

import net.luminis.quic.*;
import net.luminis.quic.frame.*;
import net.luminis.quic.packet.HandshakePacket;
import net.luminis.quic.packet.InitialPacket;
import net.luminis.quic.packet.QuicPacket;
import net.luminis.quic.packet.ShortHeaderPacket;
import org.assertj.core.data.Percentage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Percentage.withPercentage;
import static org.mockito.Mockito.*;

class PacketAssemblerTest extends AbstractSenderTest {

    public static final int MAX_PACKET_SIZE = 1232;

    private SendRequestQueue sendRequestQueue;
    private InitialPacketAssembler initialPacketAssembler;
    private PacketAssembler handshakePacketAssembler;
    private PacketAssembler oneRttPacketAssembler;
    private AckGenerator initialAckGenerator;
    private AckGenerator handshakeAckGenerator;
    private AckGenerator oneRttAckGenerator;

    
    @BeforeEach
    void initObjectUnderTest() {
        sendRequestQueue = new SendRequestQueue();
        initialAckGenerator = new AckGenerator(PnSpace.Initial, mock(Sender.class));
        initialPacketAssembler = new InitialPacketAssembler(Version.getDefault(), MAX_PACKET_SIZE, sendRequestQueue, initialAckGenerator);
        handshakeAckGenerator = new AckGenerator(PnSpace.Handshake, mock(Sender.class));
        handshakePacketAssembler = new PacketAssembler(Version.getDefault(), EncryptionLevel.Handshake, MAX_PACKET_SIZE, sendRequestQueue, handshakeAckGenerator);
        oneRttAckGenerator = new AckGenerator(PnSpace.App, mock(Sender.class));
        oneRttPacketAssembler = new PacketAssembler(Version.getDefault(), EncryptionLevel.App, MAX_PACKET_SIZE, sendRequestQueue, oneRttAckGenerator);

    }

    @Test
    void sendSingleShortPacket() {
        // Given
        byte[] destCid = new byte[] { 0x0c, 0x0a, 0x0f, 0x0e };

        // When
        sendRequestQueue.addRequest(maxSize -> new StreamFrame(0, new byte[7], true), 4 + 7, null);

        // Then
        QuicPacket packet = oneRttPacketAssembler.assemble(12000, 1232, null, destCid).get().getPacket();
        assertThat(packet).isInstanceOf(ShortHeaderPacket.class);
        assertThat(packet.getDestinationConnectionId()).isEqualTo(destCid);
        assertThat(packet.getFrames()).containsExactly(new StreamFrame(0, new byte[7], true));
        assertThat(packet.generatePacketBytes(0, keys).length).isLessThan(MAX_PACKET_SIZE);
    }

    @Test
    void sendSingleAck() {
        // Given
        oneRttAckGenerator.packetReceived(new MockPacket(0, 20, EncryptionLevel.App));

        // When
        sendRequestQueue.addAckRequest(0);    // This means the caller wants to send an _explicit_ ack.

        // Then
        QuicPacket packet = oneRttPacketAssembler.assemble(12000, 1232, null, new byte[0]).get().getPacket();
        assertThat(packet).isInstanceOf(ShortHeaderPacket.class);
        assertThat(packet.getFrames())
                .hasSize(1)
                .allSatisfy(frame -> {
            assertThat(frame).isInstanceOf(AckFrame.class);
            assertThat(((AckFrame) frame).getLargestAcknowledged()).isEqualTo(0);
        });
    }

    @Test
    void sendAckAndStreamData() {
        // Given
        oneRttAckGenerator.packetReceived(new MockPacket(0, 20, EncryptionLevel.App));
        oneRttAckGenerator.packetReceived(new MockPacket(3, 20, EncryptionLevel.App));
        oneRttAckGenerator.packetReceived(new MockPacket(8, 20, EncryptionLevel.App));
        oneRttAckGenerator.packetReceived(new MockPacket(10, 20, EncryptionLevel.App));

        // When
        sendRequestQueue.addAckRequest(0);
        sendRequestQueue.addRequest(maxSize -> new StreamFrame(0, new byte[maxSize - (3 + 2)], true),    // Stream length will be > 63, so 2 bytes for length field
                (3 + 2) + 1, null);  // Send at least 1 byte of data

        // Then
        QuicPacket packet = oneRttPacketAssembler.assemble(12000, 1232, null, new byte[0]).get().getPacket();
        assertThat(packet).isInstanceOf(ShortHeaderPacket.class);
        assertThat(packet.getFrames()).anySatisfy(frame -> {
            assertThat(frame).isInstanceOf(StreamFrame.class);
            assertThat(((StreamFrame) frame).getStreamData().length).isCloseTo(1200, withPercentage(0.5));
        });
        assertThat(packet.getFrames()).anySatisfy(frame -> {
            assertThat(frame).isInstanceOf(AckFrame.class);
            assertThat(((AckFrame) frame).getLargestAcknowledged()).isEqualTo(10);
        });
        assertThat(packet.generatePacketBytes(1, keys).length).isCloseTo(MAX_PACKET_SIZE, Percentage.withPercentage(0.25));
    }

    @Test
    void sendMultipleFrames() {
        // When
        sendRequestQueue.addRequest(new MaxStreamDataFrame(0, 0x01000000000000l), null);   // 10 bytes
        sendRequestQueue.addRequest(new MaxDataFrame(0x05000000000000l), null);              //  9 bytes
        sendRequestQueue.addRequest(maxSize -> new StreamFrame(0, new byte[maxSize - (3 + 2)], true), (3 + 2) + 1, null);  // Stream length will be > 63, so 2 bytes

        // Then
        QuicPacket packet = oneRttPacketAssembler.assemble(12000, 1232, null, new byte[0]).get().getPacket();
        assertThat(packet.getFrames()).hasOnlyElementsOfTypes(MaxStreamDataFrame.class, MaxDataFrame.class, StreamFrame.class);
        assertThat(packet.getFrames()).anySatisfy(frame -> {
            assertThat(frame).isInstanceOf(StreamFrame.class);
            // Short packet overhead is 18 to 21, so available for stream frame: 1232 - 21 - 10 - 9 = 1192. Frame overhead: 5 bytes.
            assertThat(((StreamFrame) frame).getStreamData().length).isCloseTo(1187, withPercentage(0.1));
        });
        assertThat(packet.generatePacketBytes(1, keys).length).isCloseTo(MAX_PACKET_SIZE, Percentage.withPercentage(0.25));
    }

    @Test
    void whenFirstFrameDoesNotFitFindOneThatDoes() {
        // Given
        int remainingCwndSize = 25;  // Which leaves room for approx 7 bytes payload.

        // When
        sendRequestQueue.addRequest(new MaxStreamDataFrame(0, 0x01000000000000l), null);  // 10 bytes frame length
        sendRequestQueue.addRequest(new DataBlockedFrame(60), null);  // 2 bytes frame length
        sendRequestQueue.addRequest(maxSize ->
                        new StreamFrame(0, new byte[Integer.min(maxSize, 63) - (3 + 1)], true),
                        5, null);

        // Then
        QuicPacket packet = oneRttPacketAssembler.assemble(remainingCwndSize, 1232, new byte[0], new byte[0]).get().getPacket();
        assertThat(packet.getFrames())
                .hasAtLeastOneElementOfType(DataBlockedFrame.class)
                .hasAtLeastOneElementOfType(StreamFrame.class);
        assertThat(packet.generatePacketBytes(0, keys).length).isLessThanOrEqualTo(remainingCwndSize);
    }

    @Test
    void sendHandshakePacketWithMaxLengthCrypto() {
        // Given
        byte[] srcCid = new byte[] { (byte) 0xba, (byte) 0xbe };
        byte[] destCid = new byte[] { 0x0c, 0x0a, 0x0f, 0x0e };

        // When
        sendRequestQueue.addRequest(maxSize -> new CryptoFrame(Version.getDefault(), 0, new byte[maxSize - (3 + (maxSize < 64? 1: 2))]), (3 + 2) + 1, null);

        // Then
        QuicPacket packet = handshakePacketAssembler.assemble(12000, 1232, srcCid, destCid).get().getPacket();
        assertThat(packet).isInstanceOf(HandshakePacket.class);
        assertThat(((HandshakePacket) packet).getSourceConnectionId()).isEqualTo(srcCid);
        assertThat(packet.getDestinationConnectionId()).isEqualTo(destCid);
        assertThat(packet.getFrames())
                .hasSize(1)
                .hasOnlyElementsOfTypes(CryptoFrame.class);
        assertThat(packet.generatePacketBytes(0, keys).length).isCloseTo(MAX_PACKET_SIZE, Percentage.withPercentage(0.25));
    }

    @Test
    void sendInitialPacketWithToken() {
        // Given
        byte[] srcCid = new byte[] { (byte) 0xba, (byte) 0xbe };
        byte[] destCid = new byte[] { 0x0c, 0x0a, 0x0f, 0x0e };
        initialPacketAssembler.setInitialToken(new byte[] { 0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f });

        // When
        sendRequestQueue.addRequest(maxSize -> new CryptoFrame(Version.getDefault(), 0, new byte[234]), (3 + 2) + 234, null);

        // Then
        QuicPacket packet = initialPacketAssembler.assemble(12000, 1232, srcCid, destCid).get().getPacket();
        assertThat(packet).isInstanceOf(InitialPacket.class);
        assertThat(((InitialPacket) packet).getSourceConnectionId()).isEqualTo(srcCid);
        assertThat(packet.getDestinationConnectionId()).isEqualTo(destCid);
        assertThat(packet.getFrames())
                .hasSize(1)
                .hasOnlyElementsOfTypes(CryptoFrame.class);
        assertThat(((InitialPacket) packet).getToken()).hasSize(16);
        assertThat(packet.generatePacketBytes(0, keys).length)
                .isLessThanOrEqualTo(MAX_PACKET_SIZE);
    }

    @Test
    void sendInitialPacketWithoutToken() {
        // Given
        byte[] srcCid = new byte[] { (byte) 0xba, (byte) 0xbe };
        byte[] destCid = new byte[] { 0x0c, 0x0a, 0x0f, 0x0e };

        // When
        sendRequestQueue.addRequest(maxSize -> new CryptoFrame(Version.getDefault(), 0, new byte[234]), (3 + 2) + 234, null);

        // Then
        QuicPacket packet = initialPacketAssembler.assemble(12000, 1232, srcCid, destCid).get().getPacket();
        assertThat(packet).isInstanceOf(InitialPacket.class);
        assertThat(((InitialPacket) packet).getSourceConnectionId()).isEqualTo(srcCid);
        assertThat(packet.getDestinationConnectionId()).isEqualTo(destCid);
        assertThat(packet.getFrames())
                .hasSize(1)
                .hasOnlyElementsOfTypes(CryptoFrame.class);
        assertThat(packet.generatePacketBytes(0, keys).length)
                .isLessThanOrEqualTo(MAX_PACKET_SIZE);
    }

    @Test
    void anyInitialPacketShouldHaveToken() {
        // Given
        initialPacketAssembler.setInitialToken(new byte[] { 0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07 });
        initialAckGenerator.packetReceived(new MockPacket(0, 20, EncryptionLevel.Initial));

        // When
        sendRequestQueue.addAckRequest(0);    // This means the caller wants to send an _explicit_ ack.

        // Then
        InitialPacket packet = (InitialPacket) initialPacketAssembler.assemble(12000, 1232, new byte[0], new byte[0]).get().getPacket();
        assertThat(packet.getFrames())
                .hasOnlyElementsOfTypes(AckFrame.class, Padding.class);
        assertThat(packet.getToken()).hasSize(8);
        assertThat(packet.generatePacketBytes(0, keys).length)
                .isLessThanOrEqualTo(MAX_PACKET_SIZE);
    }

    @Test
    void whenNothingToSendDelayedAckIsSendAfterDelay() throws Exception {
        // Given
        int ackDelay = 10;

        // When
        oneRttAckGenerator.packetReceived(new MockPacket(0, 20, EncryptionLevel.App));
        sendRequestQueue.addAckRequest(ackDelay);

        // Then
        Optional<SendItem> firstCheck = oneRttPacketAssembler.assemble(12000, 1232, null, new byte[]{ (byte) 0xdc, 0x1d });

        // When
        Thread.sleep(ackDelay);

        // Then
        QuicPacket packet = oneRttPacketAssembler.assemble(12000, 1232, null, new byte[]{ (byte) 0xdc, 0x1d }).get().getPacket();
        assertThat(firstCheck).isEmpty();
        assertThat(packet).isNotNull();
        assertThat(packet.getFrames()).allSatisfy(frame -> {
            assertThat(frame).isInstanceOf(AckFrame.class);
            assertThat(((AckFrame) frame).getAckDelay()).isGreaterThanOrEqualTo(ackDelay);
        });
    }

    @Test
    void whenSendingDataSentPacketWillIncludeAck() throws Exception {
        // Given
        oneRttAckGenerator.packetReceived(new MockPacket(0, 20, EncryptionLevel.App));
        oneRttAckGenerator.packetReceived(new MockPacket(3, 20, EncryptionLevel.App));
        oneRttAckGenerator.packetReceived(new MockPacket(8, 20, EncryptionLevel.App));

        // When
        sendRequestQueue.addRequest(maxSize -> new StreamFrame(0, new byte[32], true), 37, null);

        // Then
        QuicPacket packet = oneRttPacketAssembler.assemble(12000, 1232, null, new byte[0]).get().getPacket();
        assertThat(packet).isInstanceOf(ShortHeaderPacket.class);
        assertThat(packet.getFrames())
                .hasSize(2)
                .hasOnlyElementsOfTypes(StreamFrame.class, AckFrame.class)
                .anySatisfy(frame -> {
            assertThat(frame).isInstanceOf(AckFrame.class);
            assertThat(((AckFrame) frame).getLargestAcknowledged()).isEqualTo(8);
        });
    }

    @Test
    void whenNoDataToSendButAnExplicitAckIsQueueAssembleWillCreateAckOnlyPacket() throws Exception {
        // Given
        oneRttAckGenerator.packetReceived(new MockPacket(0, 20, EncryptionLevel.App));

        // When
        sendRequestQueue.addAckRequest();

        // Then
        QuicPacket packet = oneRttPacketAssembler.assemble(12000, 1232, null, new byte[0]).get().getPacket();
        assertThat(packet).isNotNull();
        assertThat(packet.getFrames())
                .hasSize(1)
                .hasOnlyElementsOfType(AckFrame.class);
    }

    @Test
    void whenExplicitAckIsAssembledNextTimeItWillNot() throws Exception {
        // Given
        oneRttAckGenerator.packetReceived(new MockPacket(0, 20, EncryptionLevel.App));

        // When
        sendRequestQueue.addAckRequest();
        oneRttPacketAssembler.assemble(12000, 1232, null, new byte[0]);

        // Then
        Optional<SendItem> optionalSendItem = oneRttPacketAssembler.assemble(12000, 1232, null, new byte[0]);
        assertThat(optionalSendItem).isEmpty();
    }

    @Test
    void whenNoDataToSendAndNoExcplicitAckToSendAssembleWillNotGenerateAckOnlyPacket() throws Exception {
        // Given
        oneRttAckGenerator.packetReceived(new MockPacket(0, 20, EncryptionLevel.App));

        // When
        // Nothing, no explicit ack requested

        // Then
        Optional<SendItem> packet = oneRttPacketAssembler.assemble(12000, 1232, null, new byte[0]);
        assertThat(packet).isEmpty();
    }

    @Test
    void whenCwndReachedNoDataIsSent() {
        // When
        sendRequestQueue.addRequest(new MaxDataFrame(102_000), null);
        int currentCwndRemaining = 16;

        // Then
        Optional<SendItem> packet = oneRttPacketAssembler.assemble(currentCwndRemaining, 1232, null, new byte[0]);
        assertThat(packet).isEmpty();
    }

    @Test
    void whenAddingProbeAndRequestListIsEmptyThenPingFrameShouldBeSent() {
        // When
        sendRequestQueue.addProbeRequest();

        // Then
        QuicPacket packet = oneRttPacketAssembler.assemble(12000, 1232, null, new byte[0]).get().getPacket();
        assertThat(packet).isNotNull();
        assertThat(packet.getFrames())
                .hasSize(1)
                .hasOnlyElementsOfType(PingFrame.class);
    }

    @Test
    void whenCwndReachedSendingProbeLeadsToSinglePing() {
        // When
        int currentCwndRemaining = 16;
        sendRequestQueue.addRequest(new MaxDataFrame(102_000), null);
        sendRequestQueue.addProbeRequest();

        // Then
        QuicPacket packet = oneRttPacketAssembler.assemble(currentCwndRemaining, 1232, null, new byte[0]).get().getPacket();
        assertThat(packet).isNotNull();
        assertThat(packet.getFrames())
                .hasSize(1)
                .hasOnlyElementsOfType(PingFrame.class);

        // And
        Optional<SendItem> another = oneRttPacketAssembler.assemble(currentCwndRemaining, 1232, null, new byte[0]);
        assertThat(another).isEmpty();
    }

    @Test
    void whenAddingProbeToNonEmptySendQueueAndCwndIsLargeEnoughTheNextPacketIsSent() {
        // When
        sendRequestQueue.addRequest(new MaxDataFrame(102_000), null);
        sendRequestQueue.addProbeRequest();

        // Then
        QuicPacket packet = oneRttPacketAssembler.assemble(60, 1232, null, new byte[0]).get().getPacket();
        assertThat(packet).isNotNull();
        assertThat(packet.getFrames())
                .hasSize(1)
                .hasOnlyElementsOfType(MaxDataFrame.class);
    }

    @Test
    void whenProbeContainsDataThisIsSendInsteadOfQueuedFrames() {
        // When
        sendRequestQueue.addRequest(new MaxDataFrame(102_000), null);
        sendRequestQueue.addProbeRequest(List.of(new CryptoFrame(Version.getDefault(), 0, new byte[100])));

        // Then
        QuicPacket packet = oneRttPacketAssembler.assemble(1200, 1232, null, new byte[0]).get().getPacket();
        assertThat(packet).isNotNull();
        assertThat(packet.getFrames())
                .hasSize(1)
                .hasOnlyElementsOfType(CryptoFrame.class);
    }

    @Test
    void testFrameCallbacksAreCalledByPacketLostCallback() {
        // Given
        Consumer<QuicFrame> callback1 = mock(Consumer.class);
        sendRequestQueue.addRequest(new MaxDataFrame(102_000), callback1);
        Consumer<QuicFrame> callback2 = mock(Consumer.class);
        sendRequestQueue.addRequest(new StreamFrame(1, new byte[924], true), callback2);

        // When
        SendItem sendItem = oneRttPacketAssembler.assemble(1200, 1232, null, new byte[0]).get();
        sendItem.getPacketLostCallback().accept(sendItem.getPacket());

        // Then
        verify(callback1).accept(argThat(frame -> frame instanceof MaxDataFrame));
        verify(callback2).accept(argThat(frame -> frame instanceof StreamFrame));
    }

    @Test
    void testInPresenceOfAckFrameAllFrameCallbacksAreCalledByPacketLostCallback() {
        // Given
        Consumer<QuicFrame> callback1 = mock(Consumer.class);
        sendRequestQueue.addRequest(new MaxDataFrame(102_000), callback1);
        Consumer<QuicFrame> callback2 = mock(Consumer.class);
        sendRequestQueue.addRequest(new StreamFrame(1, new byte[924], true), callback2);
        oneRttAckGenerator.packetReceived(new MockPacket(0, 20, EncryptionLevel.App));
        sendRequestQueue.addAckRequest(0);

        // When
        SendItem sendItem = oneRttPacketAssembler.assemble(1200, 1232, null, new byte[0]).get();
        sendItem.getPacketLostCallback().accept(sendItem.getPacket());

        // Then
        assertThat(sendItem.getPacket().getFrames()).hasAtLeastOneElementOfType(AckFrame.class);
        verify(callback1).accept(argThat(frame -> frame instanceof MaxDataFrame));
        verify(callback2).accept(argThat(frame -> frame instanceof StreamFrame));
    }

    @Test
    void createdPacketHasPacketNumberSet() {
        // Given
        sendRequestQueue.addRequest(new MaxStreamDataFrame(0, 0x01000000000000l), null);

        // When
        QuicPacket packet = oneRttPacketAssembler.assemble(1200, 1232, new byte[0], new byte[0]).get().getPacket();

        // Then
        assertThat(packet.getPacketNumber()).isNotNull();
        assertThat(packet.getPacketNumber()).isEqualTo(0);
    }

    @Test
    void consecutivePacketsHaveIncreasingPacketNumber() {
        // Given
        sendRequestQueue.addRequest(new StreamFrame(0, new byte[1160], false), f -> {});
        sendRequestQueue.addRequest(new StreamFrame(0, new byte[1160], false), f -> {});
        sendRequestQueue.addRequest(new StreamFrame(0, new byte[1160], false), f -> {});

        // When
        QuicPacket packet1 = oneRttPacketAssembler.assemble(1200, 1232, new byte[0], new byte[0]).get().getPacket();
        QuicPacket packet2 = oneRttPacketAssembler.assemble(1200, 1232, new byte[0], new byte[0]).get().getPacket();
        QuicPacket packet3 = oneRttPacketAssembler.assemble(1200, 1232, new byte[0], new byte[0]).get().getPacket();

        // Then
        assertThat(packet2.getPacketNumber()).isGreaterThan(packet1.getPacketNumber());
        assertThat(packet3.getPacketNumber()).isGreaterThan(packet2.getPacketNumber());
    }

    @Test
    void whenAckIsSendThenAckSendRequestIsCleared() {
        // Given
        oneRttAckGenerator.packetReceived(new MockPacket(0, 20, EncryptionLevel.App));
        sendRequestQueue.addAckRequest(0);

        // When
        SendItem firstSendItem = oneRttPacketAssembler.assemble(1200, 1232, null, new byte[0]).get();

        // Then
        Optional<SendItem> secondSendItem = oneRttPacketAssembler.assemble(1200, 1232, null, new byte[0]);
        assertThat(secondSendItem).isEmpty();

        assertThat(firstSendItem.getPacket().getFrames())
                .hasSize(1)
                .hasOnlyElementsOfType(AckFrame.class);
    }

    @Test
    void whenAckWasRequestedButIsNotNecessaryAnymoreDoNotSendIt() {
        // Given
        oneRttAckGenerator.packetReceived(new MockPacket(0, 20, EncryptionLevel.App));
        sendRequestQueue.addRequest(new StreamFrame(0, new byte[160], false), f -> {});

        // Simulate race condition where ack is picked up by a "normal" send
        SendItem firstSendItem = oneRttPacketAssembler.assemble(1200, 1232, null, new byte[0]).get();
        // Before the ack request was actually queued.
        sendRequestQueue.addAckRequest(0);

        // Then
        Optional<SendItem> secondSendItem = oneRttPacketAssembler.assemble(1200, 1232, null, new byte[0]);
        assertThat(secondSendItem).isEmpty();

        assertThat(firstSendItem.getPacket().getFrames())
                .hasSize(2)
                .hasAtLeastOneElementOfType(AckFrame.class);
    }

    @Test
    void whenExplicitAckDoesNotFitInPacketDontSendIt() {
        // This can happen when coalescing packets into one datagram and the space left is not enough

        // Given
        oneRttAckGenerator.packetReceived(new MockPacket(0, 20, EncryptionLevel.App));
        sendRequestQueue.addAckRequest();

        // When
        Optional<SendItem> optionalSendItem = oneRttPacketAssembler.assemble(1200, 20, null, new byte[0]);

        // Then
        assertThat(optionalSendItem).isEmpty();
        assertThat(oneRttAckGenerator.hasNewAckToSend()).isTrue();
    }

    @Test
    void explicitAckIsSentEvenIfCWndIsZero() {
        // Given
        oneRttAckGenerator.packetReceived(new MockPacket(0, 20, EncryptionLevel.App));
        sendRequestQueue.addAckRequest();

        // When
        Optional<SendItem> optionalSendItem = oneRttPacketAssembler.assemble(0, 25, null, new byte[0]);

        // Then
        assertThat(optionalSendItem).isPresent();
        assertThat(optionalSendItem.get().getPacket().getFrames()).hasOnlyElementsOfType(AckFrame.class);
    }

    @Test
    void whenExplicitAckDoesNotFitInPacketItIsSendWithNextPacket() {
        // This can happen when coalescing packets into one datagram and the space left is not enough

        // Given
        oneRttAckGenerator.packetReceived(new MockPacket(0, 20, EncryptionLevel.App));
        sendRequestQueue.addAckRequest();
        oneRttPacketAssembler.assemble(1200, 20, null, new byte[0]);

        // When
        Optional<SendItem> optionalSendItem = oneRttPacketAssembler.assemble(1200, 200, null, new byte[0]);

        // Then
        assertThat(optionalSendItem).isPresent();
        assertThat(optionalSendItem.get().getPacket().getFrames()).hasOnlyElementsOfType(AckFrame.class);
    }

    @Test
    void whenAckDoesNotFitInPacketItShouldNotBeAdded() {
        // Given
        oneRttAckGenerator.packetReceived(new MockPacket(0, 20, EncryptionLevel.App));
        sendRequestQueue.addRequest(maxSize -> new StreamFrame(0, new byte[32], true), 37, null);

        // When
        Optional<SendItem> optionalSendItem = oneRttPacketAssembler.assemble(4, 1200, null, new byte[0]);

        // Then
        assertThat(optionalSendItem).isEmpty();
    }

    @Test
    void whenAckDoesNotFitWithOtherFrameOnlyFrameShouldBeAdded() {
        // Given
        oneRttAckGenerator.packetReceived(new MockPacket(0, 20, EncryptionLevel.App));
        sendRequestQueue.addRequest(new PingFrame(), f -> {});

        // When
        Optional<SendItem> optionalSendItem = oneRttPacketAssembler.assemble(18 + 4, 1200, null, new byte[0]);

        // Then
        assertThat(optionalSendItem).isPresent();
        assertThat(optionalSendItem.get().getPacket().getFrames()).hasOnlyElementsOfType(PingFrame.class);
    }

}
