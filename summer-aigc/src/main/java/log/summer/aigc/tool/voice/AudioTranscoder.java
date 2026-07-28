package log.summer.aigc.tool.voice;

import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * 静态工具类：音频格式标准化 && 时长计算
 * 音频转码工具：PCM → WAV
 * @author bbb
 * @since 2026-07-21
 */
@Slf4j
public final class AudioTranscoder {

    private static final int WAV_HEADER_SIZE = 44;
    private static final short AUDIO_FORMAT_PCM = 1;

    private AudioTranscoder() {}

    public static byte[] pcmToWav(byte[] pcmData, int sampleRate, int bitsPerSample, int channels) {
        if (pcmData == null || pcmData.length == 0) {
            throw new IllegalArgumentException("PCM 数据不能为空");
        }

        int dataSize = pcmData.length;
        int fileSize = WAV_HEADER_SIZE + dataSize;
        int bytesPerSample = bitsPerSample / 8;
        int byteRate = sampleRate * channels * bytesPerSample;
        int blockAlign = channels * bytesPerSample;

        try (ByteArrayOutputStream bos = new ByteArrayOutputStream(fileSize);
             DataOutputStream dos = new DataOutputStream(bos)) {

            writeFourCC(dos, "RIFF");
            writeIntLE(dos, fileSize - 8);
            writeFourCC(dos, "WAVE");

            writeFourCC(dos, "fmt ");
            writeIntLE(dos, 16);
            writeShortLE(dos, AUDIO_FORMAT_PCM);
            writeShortLE(dos, (short) channels);
            writeIntLE(dos, sampleRate);
            writeIntLE(dos, byteRate);
            writeShortLE(dos, (short) blockAlign);
            writeShortLE(dos, (short) bitsPerSample);

            writeFourCC(dos, "data");
            writeIntLE(dos, dataSize);

            dos.write(pcmData);
            dos.flush();
            byte[] wav = bos.toByteArray();

            log.debug("[AUDIO] PCM→WAV 转码完成 | pcmSize={} | wavSize={} | header=44 | sampleRate={} | bits={} | channels={}",
                    dataSize, wav.length, sampleRate, bitsPerSample, channels);
            return wav;

        } catch (IOException e) {
            throw new AudioTranscoderException("WAV 封装失败", e);
        }
    }

    public static int calculateDurationMs(int pcmByteCount, int sampleRate, int bitsPerSample, int channels) {
        if (pcmByteCount <= 0 || sampleRate <= 0 || bitsPerSample <= 0 || channels <= 0) {
            return 500;
        }
        long numerator = (long) pcmByteCount * 8000L;
        long denominator = (long) sampleRate * bitsPerSample * channels;
        long durationMs = numerator / denominator;
        return (int) Math.max(500, durationMs);
    }

    public static int calculateDurationFromWav(int wavByteCount, int sampleRate, int bitsPerSample, int channels) {
        int pcmSize = Math.max(0, wavByteCount - WAV_HEADER_SIZE);
        return calculateDurationMs(pcmSize, sampleRate, bitsPerSample, channels);
    }

    private static void writeIntLE(DataOutputStream dos, int value) throws IOException {
        dos.writeByte(value & 0xFF);
        dos.writeByte((value >> 8) & 0xFF);
        dos.writeByte((value >> 16) & 0xFF);
        dos.writeByte((value >> 24) & 0xFF);
    }

    private static void writeShortLE(DataOutputStream dos, short value) throws IOException {
        dos.writeByte(value & 0xFF);
        dos.writeByte((value >> 8) & 0xFF);
    }

    private static void writeFourCC(DataOutputStream dos, String fourCC) throws IOException {
        dos.writeBytes(fourCC);
    }

    public static class AudioTranscoderException extends RuntimeException {
        public AudioTranscoderException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
