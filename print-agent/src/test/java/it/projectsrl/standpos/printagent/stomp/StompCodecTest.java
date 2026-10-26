package it.projectsrl.standpos.printagent.stomp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class StompCodecTest {

    @Test
    void encodesConnectWithoutEscaping() {
        String frame = StompCodec.encode(StompFrame.builder("CONNECT")
                .header("accept-version", "1.2")
                .header("host", "pos.example.org")
                .header("heart-beat", "10000,10000")
                .build());

        assertThat(frame).isEqualTo("CONNECT\naccept-version:1.2\nhost:pos.example.org\nheart-beat:10000,10000\n\n\0");
    }

    @Test
    void encodesSendWithEscapedHeadersAndUtf8ContentLength() {
        String frame = StompCodec.encode(StompFrame.builder("SEND")
                .header("destination", "/app/agent/ack")
                .header("x-note", "a:b\\c\nd\re")
                .body("{\"e\":\"€\"}")
                .build());

        assertThat(frame).isEqualTo("SEND\ndestination:/app/agent/ack\nx-note:a\\cb\\\\c\\nd\\re\n"
                + "content-length:11\n\n{\"e\":\"€\"}\0");
    }

    @Test
    void decodesFramesSplitAcrossMessagesAndHeartbeats() {
        StompCodec.Decoder decoder = new StompCodec.Decoder();

        assertThat(decoder.feed("\n")).containsExactly(new StompCodec.HeartbeatEvent());
        assertThat(decoder.feed("MESSAGE\ndestination:/user/queue/print-jobs\nsubscr")).isEmpty();
        assertThat(decoder.feed("iption:jobs\n\n{\"a\":")).isEmpty();
        List<StompCodec.Event> events = decoder.feed("1}\0\r\n\n");

        assertThat(events).hasSize(3);
        StompFrame frame = ((StompCodec.FrameEvent) events.getFirst()).frame();
        assertThat(frame.command()).isEqualTo("MESSAGE");
        assertThat(frame.header("subscription")).isEqualTo("jobs");
        assertThat(frame.body()).isEqualTo("{\"a\":1}");
        assertThat(events.subList(1, 3)).containsOnly(new StompCodec.HeartbeatEvent());
    }

    @Test
    void decodesSeveralFramesInOneMessage() {
        List<StompCodec.Event> events = new StompCodec.Decoder()
                .feed("CONNECTED\nversion:1.2\nheart-beat:0,0\n\n\0\nRECEIPT\nreceipt-id:1\n\n\0");

        assertThat(events).hasSize(3);
        assertThat(((StompCodec.FrameEvent) events.get(0)).frame().header("heart-beat")).isEqualTo("0,0");
        assertThat(events.get(1)).isInstanceOf(StompCodec.HeartbeatEvent.class);
        assertThat(((StompCodec.FrameEvent) events.get(2)).frame().command()).isEqualTo("RECEIPT");
    }

    @Test
    void honoursContentLengthInUtf8BytesEvenWithNulInTheBody() {
        StompCodec.Decoder decoder = new StompCodec.Decoder();
        // "€\0x" is 3 + 1 + 1 = 5 bytes
        assertThat(decoder.feed("MESSAGE\ncontent-length:5\n\n€\0")).isEmpty();
        List<StompCodec.Event> events = decoder.feed("x\0");

        assertThat(((StompCodec.FrameEvent) events.getFirst()).frame().body()).isEqualTo("€\0x");
    }

    @Test
    void roundTripsEscapedHeadersButNotInConnected() {
        StompFrame original = StompFrame.builder("ERROR").header("message", "bad:thing\nhere\\").body("x").build();
        StompFrame back = single(new StompCodec.Decoder().feed(StompCodec.encode(original)));
        StompFrame connected = single(new StompCodec.Decoder().feed("CONNECTED\nserver:a\\cb\n\n\0"));

        assertThat(back.header("message")).isEqualTo("bad:thing\nhere\\");
        assertThat(back.body()).isEqualTo("x");
        assertThat(connected.header("server")).isEqualTo("a\\cb");
    }

    @Test
    void firstRepeatedHeaderWinsAndCrLfIsAccepted() {
        StompFrame frame = single(new StompCodec.Decoder().feed("MESSAGE\r\nfoo:1\r\nfoo:2\r\n\r\nbody\0"));

        assertThat(frame.header("foo")).isEqualTo("1");
        assertThat(frame.body()).isEqualTo("body");
    }

    @Test
    void rejectsUndefinedEscapes() {
        assertThatThrownBy(() -> new StompCodec.Decoder().feed("MESSAGE\nfoo:a\\tb\n\n\0"))
                .isInstanceOf(StompProtocolException.class);
    }

    @Test
    void negotiatesHeartBeats() {
        assertThat(StompSession.negotiate("0,0")).containsExactly(0, 0);
        assertThat(StompSession.negotiate(null)).containsExactly(0, 0);
        assertThat(StompSession.negotiate("5000,20000")).containsExactly(20000, 10000);
        assertThat(StompSession.negotiate("25000,5000")).containsExactly(10000, 25000);
    }

    private static StompFrame single(List<StompCodec.Event> events) {
        assertThat(events).hasSize(1);
        return ((StompCodec.FrameEvent) events.getFirst()).frame();
    }
}
