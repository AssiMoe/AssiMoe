/*
 * PhoneMic for OBS Studio 33+ (Windows x64)
 * Copyright (c) 2026 PhoneMic Community Project
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
constexpr size_t SAMPLES_PER_FRAME = 480;
constexpr size_t PCM_BYTES = SAMPLES_PER_FRAME * 2;
constexpr size_t HEADER_BYTES = 20;
constexpr size_t PACKET_BYTES = HEADER_BYTES + PCM_BYTES;
constexpr uint32_t DEFAULT_CODE = 24681357;
using Clock = std::chrono::steady_clock;
using AudioBlock = std::array<uint8_t, PCM_BYTES>;

uint16_t rd16(const uint8_t *p)
{
    return static_cast<uint16_t>(p[0]) |
           (static_cast<uint16_t>(p[1]) << 8);
}
uint32_t rd32(const uint8_t *p)
{
    return static_cast<uint32_t>(p[0]) |
           (static_cast<uint32_t>(p[1]) << 8) |
           (static_cast<uint32_t>(p[2]) << 16) |
           (static_cast<uint32_t>(p[3]) << 24);
}
void wr32(uint8_t *p, uint32_t v)
{
    for (int i = 0; i < 4; ++i)
        p[i] = static_cast<uint8_t>(v >> (8 * i));
}

struct PhoneMic {
    obs_source_t *source = nullptr;
    std::atomic<bool> stopping{false};
    std::thread receiver;
    int port = 49888;
    uint32_t code = DEFAULT_CODE;
};

void output_pcm(PhoneMic *mic, const AudioBlock &samples)
{
    obs_source_audio audio{};
    audio.data[0] = const_cast<uint8_t *>(samples.data());
    audio.frames = SAMPLES_PER_FRAME;
    audio.samples_per_sec = SAMPLE_RATE;
    audio.speakers = SPEAKERS_MONO;
    audio.format = AUDIO_FORMAT_16BIT;
    audio.timestamp = os_gettime_ns() - 10000000ULL;
    obs_source_output_audio(mic->source, &audio);
}

void run_receiver(PhoneMic *mic)
{
    SOCKET fd = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
    if (fd == INVALID_SOCKET) {
        blog(LOG_ERROR, "[PhoneMic] Socket konnte nicht erstellt werden: %d", WSAGetLastError());
        return;
    }

    sockaddr_in listen_addr{};
    listen_addr.sin_family = AF_INET;
    listen_addr.sin_port = htons(static_cast<u_short>(mic->port));
    listen_addr.sin_addr.s_addr = htonl(INADDR_ANY);

    if (bind(fd, reinterpret_cast<const sockaddr *>(&listen_addr), sizeof(listen_addr)) == SOCKET_ERROR) {
        blog(LOG_ERROR, "[PhoneMic] UDP-Port %d kann nicht gebunden werden (WSA %d)",
             mic->port, WSAGetLastError());
        closesocket(fd);
        return;
    }
    blog(LOG_INFO, "[PhoneMic] Mikrofonempfang auf UDP %d aktiviert", mic->port);

    std::unordered_map<uint32_t, AudioBlock> jitter;
    AudioBlock silence{};
    uint32_t wanted_seq = 0;
    uint32_t active_session = 0;
    uint32_t received_count = 0;
    bool active = false;
    auto last_received = Clock::now();
    auto next_playout = Clock::now();

    while (!mic->stopping.load(std::memory_order_relaxed)) {
        fd_set set;
        FD_ZERO(&set);
        FD_SET(fd, &set);
        timeval timeout{0, 2000};
        int selected = select(0, &set, nullptr, nullptr, &timeout);

        if (selected == SOCKET_ERROR) {
            blog(LOG_WARNING, "[PhoneMic] UDP select fehlgeschlagen (WSA %d)", WSAGetLastError());
            break;
        }

        if (selected > 0 && FD_ISSET(fd, &set)) {
            std::array<uint8_t, PACKET_BYTES + 32> buf{};
            sockaddr_in sender{};
            int sender_size = sizeof(sender);
            int count = recvfrom(fd, reinterpret_cast<char *>(buf.data()),
                                 static_cast<int>(buf.size()), 0,
                                 reinterpret_cast<sockaddr *>(&sender), &sender_size);

            if (count == static_cast<int>(PACKET_BYTES) &&
                std::memcmp(buf.data(), "AMIC", 4) == 0 &&
                buf[4] == 1 &&
                rd16(buf.data() + 6) == PCM_BYTES &&
                rd32(buf.data() + 12) == mic->code) {

                const auto now = Clock::now();
                const uint32_t seq = rd32(buf.data() + 8);
                const uint32_t session = rd32(buf.data() + 16);

                if (!active || session != active_session ||
                    now - last_received > std::chrono::milliseconds(650)) {
                    jitter.clear();
                    wanted_seq = seq;
                    active_session = session;
                    next_playout = now + std::chrono::milliseconds(45);
                    received_count = 0;
                    active = true;
                }
                last_received = now;
                const auto delta = static_cast<int32_t>(seq - wanted_seq);
                if (delta >= 0 && delta < 64) {
                    AudioBlock pcm{};
                    std::memcpy(pcm.data(), buf.data() + HEADER_BYTES, PCM_BYTES);
                    jitter[seq] = pcm;
                }

                if (++received_count % 40 == 0) {
                    uint8_t response[12] = {'A', 'M', 'O', 'K'};
                    wr32(response + 4, mic->code);
                    wr32(response + 8, active_session);
                    sendto(fd, reinterpret_cast<const char *>(response),
                           sizeof(response), 0,
                           reinterpret_cast<const sockaddr *>(&sender), sender_size);
                }
            }
        }

        if (!active)
            continue;
        const auto now = Clock::now();
        if (now - last_received > std::chrono::milliseconds(650)) {
            jitter.clear();
            active = false;
            continue;
        }
        if (now - next_playout > std::chrono::milliseconds(100)) {
            jitter.clear();
            active = false;
            continue;
        }

        while (active && now >= next_playout &&
               !mic->stopping.load(std::memory_order_relaxed)) {
            auto it = jitter.find(wanted_seq);
            if (it != jitter.end()) {
                output_pcm(mic, it->second);
                jitter.erase(it);
            } else {
                output_pcm(mic, silence);
            }
            ++wanted_seq;
            next_playout += std::chrono::milliseconds(10);
        }
    }

    closesocket(fd);
    blog(LOG_INFO, "[PhoneMic] Mikrofonempfang auf UDP %d beendet", mic->port);
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
    start_receiver(mic);
    return mic;
}

void source_destroy(void *data)
{
    auto *mic = static_cast<PhoneMic *>(data);
    if (mic) {
        stop_receiver(mic);
        delete mic;
    }
}

void source_update(void *data, obs_data_t *settings)
{
    auto *mic = static_cast<PhoneMic *>(data);
    if (!mic)
        return;

    const auto port = static_cast<int>(obs_data_get_int(settings, "udp_port"));
    const auto code = static_cast<uint32_t>(obs_data_get_int(settings, "pair_code"));
    if (port == mic->port && code == mic->code)
        return;

    stop_receiver(mic);
    mic->port = port;
    mic->code = code;
    start_receiver(mic);
}

void source_defaults(obs_data_t *settings)
{
    obs_data_set_default_int(settings, "udp_port", 49888);
    obs_data_set_default_int(settings, "pair_code", DEFAULT_CODE);
}

obs_properties_t *source_properties(void *)
{
    obs_properties_t *p = obs_properties_create();
    obs_properties_add_int(p, "udp_port", obs_module_text("PhoneMic.Port"), 1024, 65535, 1);
    obs_properties_add_int(p, "pair_code", obs_module_text("PhoneMic.PairCode"), 10000000, 99999999, 1);
    return p;
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

    blog(LOG_INFO, "[PhoneMic] OBS-Audioquelle registriert");
    return true;
}

void obs_module_unload(void)
{
    WSACleanup();
}
