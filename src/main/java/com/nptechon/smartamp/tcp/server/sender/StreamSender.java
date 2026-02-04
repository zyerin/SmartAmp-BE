package com.nptechon.smartamp.tcp.server.sender;

import com.nptechon.smartamp.tcp.codec.CommandPacketCodec;
import com.nptechon.smartamp.tcp.codec.StreamFrameEncoder;
import com.nptechon.smartamp.tcp.protocol.AmpOpcode;
import com.nptechon.smartamp.tcp.protocol.DateTime7;
import com.nptechon.smartamp.tcp.protocol.payload.StreamType;
import com.nptechon.smartamp.tcp.server.session.TcpSessionManager;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;

@Slf4j
@Component
@RequiredArgsConstructor
public class StreamSender {

    private static final int CHUNK = 508;

    private final TcpSessionManager tcpSessionManager;

    /**
     * MP3 InputStream을 amp로 푸시한다.
     * - 0x04(stream type) Command 먼저 전송
     * - FS/FD/FE 순서로 512바이트 스트림 프레임 전송
     */
    public void pushMp3(int ampId, StreamType type, InputStream mp3In) throws IOException {
        Channel ch = tcpSessionManager.get(ampId);
        if (ch == null || !ch.isActive()) {
            throw new IllegalStateException("AMP not connected: " + ampId);
        }

        // ⭐ Netty 이벤트루프에서 순서 보장하며 보내기
        ch.eventLoop().execute(() -> {
            try {
                sendStreamType(ch, ampId, type);
                sendStreamFrames(ch, mp3In);
            } catch (Exception e) {
                log.error("pushMp3 failed ampId={}", ampId, e);
                try {
                    mp3In.close();
                } catch (IOException ignore) {
                }
            }
        });
    }

    private void sendStreamType(Channel ch, int ampId, StreamType type) {
        byte[] dt7 = DateTime7.now();
        byte[] payload = new byte[]{type.code()};

        ByteBuf pkt = CommandPacketCodec.encode(
                ch.alloc(),
                ampId,
                dt7,
                AmpOpcode.STREAM_TYPE,
                payload
        );

        ch.writeAndFlush(pkt);
        log.info("sent stream type(0x04) ampId={} type={}", ampId, type);
    }

    private void sendStreamFrames(Channel ch, InputStream mp3In) throws IOException {
        int seq = 0;

        // 1) FS (No Stream Data)
        ch.write(StreamFrameEncoder.encode(ch.alloc(), 'S', seq++, null));

        // 2) FD ... (MP3 chunks)
        byte[] buf = new byte[CHUNK];
        while (true) {
            int read = readFullyOrLess(mp3In, buf);
            if (read <= 0) break;

            // backpressure: 너무 빠르면 메모리 쌓임 → waterMark 체크
            waitWritable(ch);

            byte[] chunk = (read == CHUNK) ? buf.clone() : copyOf(buf, read);
            ch.write(StreamFrameEncoder.encode(ch.alloc(), 'D', seq++, chunk));
        }

        // 3) FE (No Stream Data)
        ChannelFuture f = ch.writeAndFlush(
                StreamFrameEncoder.encode(ch.alloc(), 'E', seq++, null)
        );

        f.addListener(done -> {
            try {
                mp3In.close();
            } catch (IOException ignore) {
            }
        });
    }

    /**
     * 채널이 쓰기 불가능(버퍼 가득)하면 잠깐 기다림.
     * (실무에서 안정성에 엄청 중요)
     */
    private void waitWritable(Channel ch) {
        int spin = 0;
        while (!ch.isWritable()) {
            if (++spin > 50) break;
            try {
                Thread.sleep(2);
            } catch (InterruptedException ignored) {
            }
        }
    }

    private static int readFullyOrLess(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        int remaining = buf.length;

        while (remaining > 0) {
            int r = in.read(buf, off, remaining);
            if (r == -1) return (off == 0) ? -1 : off;

            off += r;
            remaining -= r;

            if (r == 0) break;
        }
        return off;
    }

    private static byte[] copyOf(byte[] src, int len) {
        byte[] out = new byte[len];
        System.arraycopy(src, 0, out, 0, len);
        return out;
    }
}
