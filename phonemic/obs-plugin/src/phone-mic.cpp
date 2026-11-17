/*
 * PhoneMic for OBS Studio (Windows x64)
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
#define WIN32_LEAN_AND_MEAN
#include <winsock2.h>
#include <ws2tcpip.h>

#include <obs-module.h>
#include <util/platform.h>

#include <array>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <cstring>
#include <thread>
#include <unordered_map>

OBS_DECLARE_MODULE()
OBS_MODULE_USE_DEFAULT_LOCALE("phone-mic-obs", "de-DE")

namespace {
constexpr uint32_t SAMPLE_RATE = 48000;
constexpr uint64_t FRAME_NS = 10000000ULL;
constexpr size_t PCM_BYTES = 960;
constexpr size_t HEADER_BYTES = 20;
constexpr size_t PACKET_BYTES = HEADER_BYTES + PCM_BYTES;
constexpr size_t SAMPLES_PER_FRAME = PCM_BYTES / 2;
constexpr uint32_t DEFAULT_CODE = 24681357;
constexpr int MAX_AHEAD_FRAMES = 64;
using Clock = std::chrono::steady_clock;
using AudioBlock = std::array<uint8_t, PCM_BYTES>;

uint16_t rd16(const uint8_t *p)
{
    return static_cast<uint16_t>(p[0]) |
           static_cast<uint16_t>(p[1] << 8);
}
uint32_t rd32(const uint8_t *p)
{
    return static_cast<uint32_t>(p[0]) |
           (static_cast<uint32_t>(p[1]) << 8) |
           (static_cast<uint32_t>(p[2]) << 16) |
           (static_cast<uint32_t>(p[3]) << 24);
}
void wr32(uint8_t *p, uint32_t n)
{
    for (int i = 0; i < 4; ++i)
        p[i] = static_cast<uint8_t>(n >> (8 * i));
}

struct PhoneMic {
    obs_source_t *source = nullptr;
    std::atomic<bool> stopping{false};
    std::thread receiver;
    int port = 49888;
    uint32_t code = DEFAULT_CODE;
    int buffer_ms = 140;
};

void output_pcm(PhoneMic *mic, const AudioBlock &pcm, uint64_t timestamp)
{
    obs_source_audio audio{};
    audio.data[0] = const_cast<uint8_t *>(pcm.data());
    audio.frames = static_cast<uint32_t>(SAMPLES_PER_FRAME);
    audio.samples_per_sec = SAMPLE_RATE;
    audio.speakers = SPEAKERS_MONO;
    audio.format = AUDIO_FORMAT_16BIT;
    audio.timestamp = timestamp;
    obs_source_output_audio(mic->source, &audio);
}

void run_receiver(PhoneMic *mic)
{
    SOCKET fd = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
    if (fd == INVALID_SOCKET) {
        blog(LOG_ERROR, "[PhoneMic] socket fehlgeschlagen (WSA %d)", WSAGetLastError());
        return;
    }

    int receive_buffer = 1024 * 1024;
    setsockopt(fd, SOL_SOCKET, SO_RCVBUF,
               reinterpret_cast<const char *>(&receive_buffer), sizeof(receive_buffer));

    sockaddr_in listen_addr{};
    listen_addr.sin_family = AF_INET;
    listen_addr.sin_port = htons(static_cast<u_short>(mic->port));
    listen_addr.sin_addr.s_addr = htonl(INADDR_ANY);
    if (bind(fd, reinterpret_cast<const sockaddr *>(&listen_addr), sizeof(listen_addr)) == SOCKET_ERROR) {
        blog(LOG_ERROR, "[PhoneMic] UDP Port %d kann nicht gebunden werden (WSA %d)",
             mic->port, WSAGetLastError());
        closesocket(fd);
        return;
    }
    blog(LOG_INFO, "[PhoneMic v1.1] UDP %d, Audiopuffer %d ms", mic->port, mic->buffer_ms);

    std::unordered_map<uint32_t, AudioBlock> queue;
    AudioBlock silence{};
    AudioBlock previous{};
    uint32_t wanted = 0, session_id = 0, ack_count = 0;
    uint64_t next_audio_ts = 0;
    uint64_t packets = 0, missing = 0, resets = 0;
    int consecutive_missing = 0;
    bool active = false, have_previous = false;
    auto last_packet = Clock::now();
    auto next_playout = last_packet;
    auto last_stats = last_packet;
    const auto playout_delay = std::chrono::milliseconds(mic->buffer_ms);

    auto reset = [&](uint32_t seq, uint32_t session, Clock::time_point now) {
        queue.clear();
        wanted = seq;
        session_id = session;
        ack_count = 0;
        next_audio_ts = 0;
        consecutive_missing = 0;
        have_previous = false;
        active = true;
        next_playout = now + playout_delay;
        ++resets;
    };

    while (!mic->stopping.load(std::memory_order_relaxed)) {
        fd_set fds;
        FD_ZERO(&fds);
        FD_SET(fd, &fds);
        timeval tv{0, 2000};
        const int ready = select(0, &fds, nullptr, nullptr, &tv);
        if (ready == SOCKET_ERROR) {
            blog(LOG_WARNING, "[PhoneMic] Netzwerkeingang beendet (WSA %d)", WSAGetLastError());
            break;
        }

        if (ready > 0 && FD_ISSET(fd, &fds)) {
            std::array<uint8_t, PACKET_BYTES + 16> bytes{};
            sockaddr_in sender{};
            int sender_len = sizeof(sender);
            const int n = recvfrom(fd, reinterpret_cast<char *>(bytes.data()),
                                   static_cast<int>(bytes.size()), 0,
                                   reinterpret_cast<sockaddr *>(&sender), &sender_len);
            if (n == static_cast<int>(PACKET_BYTES) &&
                std::memcmp(bytes.data(), "AMIC", 4) == 0 && bytes[4] == 1 &&
                rd16(bytes.data() + 6) == PCM_BYTES &&
                rd32(bytes.data() + 12) == mic->code) {

                const auto now = Clock::now();
                const uint32_t seq = rd32(bytes.data() + 8);
                const uint32_t session = rd32(bytes.data() + 16);
                bool new_session = !active || session != session_id ||
                    now - last_packet > std::chrono::milliseconds(950);
                if (new_session)
                    reset(seq, session, now);

                int32_t diff = static_cast<int32_t>(seq - wanted);
                if (diff >= MAX_AHEAD_FRAMES) {
                    reset(seq, session, now);
                    diff = 0;
                    new_session = true;
                }

                last_packet = now;
                if (diff >= 0 && diff < MAX_AHEAD_FRAMES && queue.size() < MAX_AHEAD_FRAMES) {
                    AudioBlock pcm{};
                    std::memcpy(pcm.data(), bytes.data() + HEADER_BYTES, PCM_BYTES);
                    queue.insert_or_assign(seq, pcm);
                }
                ++packets;

                if (new_session || (++ack_count % 10) == 0) {
                    uint8_t response[12] = {'A', 'M', 'O', 'K'};
                    wr32(response + 4, mic->code);
                    wr32(response + 8, session_id);
                    sendto(fd, reinterpret_cast<const char *>(response),
                           sizeof(response), 0,
                           reinterpret_cast<const sockaddr *>(&sender), sender_len);
                }
            }
        }

        if (!active)
            continue;
        auto now = Clock::now();
        if (now - last_packet > std::chrono::milliseconds(950)) {
            queue.clear();
            active = false;
            continue;
        }

        if (now - next_playout > std::chrono::milliseconds(100)) {
            queue.clear();
            active = false;
            blog(LOG_WARNING, "[PhoneMic] Audiotakt blockiert, empfange Stream neu");
            continue;
        }

        int frames_this_round = 0;
        while (now >= next_playout && frames_this_round++ < 8 &&
               !mic->stopping.load(std::memory_order_relaxed)) {
            AudioBlock pcm = silence;
            auto it = queue.find(wanted);
            if (it != queue.end()) {
                pcm = it->second;
                previous = pcm;
                have_previous = true;
                consecutive_missing = 0;
                queue.erase(it);
            } else {
                ++missing;
                ++consecutive_missing;
                // A single lost 10-ms packet sounds less abrupt with a faded copy.
                if (consecutive_missing == 1 && have_previous) {
                    for (size_t i = 0; i < PCM_BYTES; i += 2) {
                        const int16_t sample = static_cast<int16_t>(
                            static_cast<uint16_t>(previous[i]) |
                            (static_cast<uint16_t>(previous[i + 1]) << 8));
                        const int16_t faded = static_cast<int16_t>(sample / 3);
                        pcm[i] = static_cast<uint8_t>(faded);
                        pcm[i + 1] = static_cast<uint8_t>(faded >> 8);
                    }
                }
            }

            const uint64_t obs_now = os_gettime_ns();
            if (next_audio_ts == 0 ||
                obs_now > next_audio_ts + 150000000ULL ||
                next_audio_ts > obs_now + 20000000ULL) {
                next_audio_ts = obs_now > FRAME_NS ? obs_now - FRAME_NS : obs_now;
            }
            output_pcm(mic, pcm, next_audio_ts);
            next_audio_ts += FRAME_NS;
            ++wanted;
            next_playout += std::chrono::milliseconds(10);

            if (consecutive_missing >= 30) {
                queue.clear();
                active = false;
                break;
            }
        }

        if (now - last_stats >= std::chrono::seconds(30)) {
            blog(LOG_INFO, "[PhoneMic] empfangen=%llu, ersetzt=%llu, resync=%llu, Warteschlange=%zu",
                 static_cast<unsigned long long>(packets),
                 static_cast<unsigned long long>(missing),
                 static_cast<unsigned long long>(resets), queue.size());
            last_stats = now;
        }
    }

    closesocket(fd);
    blog(LOG_INFO, "[PhoneMic] Empfaenger UDP %d beendet", mic->port);
}

void start_receiver(PhoneMic *mic)
{
    mic->stopping.store(false);
    mic->receiver = std::thread(run_receiver, mic);
}
void stop_receiver(PhoneMic *mic)
{
    mic->stopping.store(true);
    if (mic->receiver.joinable())
        mic->receiver.join();
}
const char *source_name(void *)
{
    return obs_module_text("PhoneMic.SourceName");
}
void *source_create(obs_data_t *settings, obs_source_t *source)
{
    auto *mic = new PhoneMic;
    mic->source = source;
    mic->port = static_cast<int>(obs_data_get_int(settings, "udp_port"));
    mic->code = static_cast<uint32_t>(obs_data_get_int(settings, "pair_code"));
    mic->buffer_ms = static_cast<int>(obs_data_get_int(settings, "buffer_ms"));
    start_receiver(mic);
    return mic;
}
void source_destroy(void *data)
{
    auto *mic = static_cast<PhoneMic *>(data);
    if (!mic)
        return;
    stop_receiver(mic);
    delete mic;
}
void source_update(void *data, obs_data_t *settings)
{
    auto *mic = static_cast<PhoneMic *>(data);
    if (!mic)
        return;
    const auto port = static_cast<int>(obs_data_get_int(settings, "udp_port"));
    const auto code = static_cast<uint32_t>(obs_data_get_int(settings, "pair_code"));
    const auto buffer_ms = static_cast<int>(obs_data_get_int(settings, "buffer_ms"));
    if (mic->port == port && mic->code == code && mic->buffer_ms == buffer_ms)
        return;
    stop_receiver(mic);
    mic->port = port;
    mic->code = code;
    mic->buffer_ms = buffer_ms;
    start_receiver(mic);
}
void source_defaults(obs_data_t *settings)
{
    obs_data_set_default_int(settings, "udp_port", 49888);
    obs_data_set_default_int(settings, "pair_code", DEFAULT_CODE);
    obs_data_set_default_int(settings, "buffer_ms", 140);
}
obs_properties_t *source_properties(void *)
{
    obs_properties_t *properties = obs_properties_create();
    obs_properties_add_int(properties, "udp_port", obs_module_text("PhoneMic.Port"), 1024, 65535, 1);
    obs_properties_add_int(properties, "pair_code", obs_module_text("PhoneMic.PairCode"), 10000000, 99999999, 1);
    obs_properties_add_int_slider(properties, "buffer_ms", obs_module_text("PhoneMic.Buffer"), 60, 300, 10);
    return properties;
}
} // namespace

bool obs_module_load(void)
{
    WSADATA wsa{};
    if (WSAStartup(MAKEWORD(2, 2), &wsa) != 0)
        return false;

    obs_source_info info{};
    info.id = "phone_mic_udp";
    info.type = OBS_SOURCE_TYPE_INPUT;
    info.output_flags = OBS_SOURCE_AUDIO;
    info.get_name = source_name;
    info.create = source_create;
    info.destroy = source_destroy;
    info.update = source_update;
    info.get_defaults = source_defaults;
    info.get_properties = source_properties;
    info.icon_type = OBS_ICON_TYPE_AUDIO_INPUT;
    obs_register_source(&info);

    blog(LOG_INFO, "[PhoneMic v1.1] OBS-Audioquelle registriert");
    return true;
}
void obs_module_unload(void)
{
    WSACleanup();
}
