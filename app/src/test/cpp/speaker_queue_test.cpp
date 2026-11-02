// SPDX-License-Identifier: LGPL-3.0-or-later

#include "speaker_queue.h"

#include <gtest/gtest.h>
#include <sodium.h>

#include <atomic>
#include <chrono>
#include <cstdint>
#include <memory>
#include <mutex>
#include <thread>
#include <vector>

#include "audio_codec.h"
#include "receive_path.h"
#include "session_table.h"

namespace {

using Clock = std::chrono::steady_clock;

constexpr uint8_t kToken[4] = {1, 2, 3, 4};
constexpr int kUnknownHandle = 4242;
constexpr auto kWaitLimit = std::chrono::seconds(5);
constexpr uint16_t kStreamFrames = 50;

struct Delivered {
    int handle;
    uint8_t ctrlIdx;
    int lane;
    bool concealed;
};

class RecordingSpeaker final : public dish_receive::SpeakerSink {
  public:
    void pcm(const int handle, const uint8_t ctrlIdx, const int lane, const int16_t*,
             const bool concealed) override {
        std::lock_guard<std::mutex> lock(mtx_);
        frames.push_back(Delivered{handle, ctrlIdx, lane, concealed});
    }

    size_t count() {
        std::lock_guard<std::mutex> lock(mtx_);
        return frames.size();
    }

    std::vector<Delivered> frames;

  private:
    std::mutex mtx_;
};

class SpeakerQueueTest : public ::testing::Test {
  protected:
    static void SetUpTestSuite() { ASSERT_GE(sodium_init(), 0); }

    void SetUp() override {
        handle = table.open("127.0.0.1", 9);
        ASSERT_GT(handle, 0);
        uint8_t key[32] = {};
        dish_session::setConnectionParams(table.find(handle), kToken, key, 3);
        encoder = dish_audio::OpusStreamEncoder::create(dish_audio::Stream::Speaker);
        ASSERT_NE(encoder, nullptr);
    }

    void TearDown() override { table.close(handle); }

    // One 20 ms frame of silence as the satellite would encode it.
    std::vector<uint8_t> packet() {
        const std::vector<int16_t> stereo(dish_audio::AUDIO_SPEAKER_FRAME_SAMPLES, 0);
        std::vector<uint8_t> out(512);
        const size_t len =
            encoder->encode(stereo.data(), dish_audio::AUDIO_FRAME_SAMPLES, out.data(), out.size());
        out.resize(len);
        return out;
    }

    void push(const int to, const uint16_t seq, const int lane = dish_receive::AUDIO_LANE_SPEAKER) {
        const std::vector<uint8_t> opus = packet();
        queue.push(to, lane, dish_wire::AudioFrameHeader{2, seq}, opus.data(), opus.size());
    }

    dish_session::SessionTable table;
    int handle = -1;
    std::unique_ptr<dish_audio::OpusStreamEncoder> encoder;
    dish_receive::SpeakerQueue queue;
    RecordingSpeaker speaker;
};

} // namespace

TEST_F(SpeakerQueueTest, AQueuedFrameIsDecodedForItsSessionControllerAndLane) {
    push(handle, 0);
    push(handle, 0, dish_receive::AUDIO_LANE_HAPTICS);
    EXPECT_EQ(queue.drain(table, speaker), 2u);
    ASSERT_EQ(speaker.frames.size(), 2u);
    EXPECT_EQ(speaker.frames[0].handle, handle);
    EXPECT_EQ(speaker.frames[0].ctrlIdx, 2);
    EXPECT_EQ(speaker.frames[0].lane, dish_receive::AUDIO_LANE_SPEAKER);
    EXPECT_EQ(speaker.frames[1].lane, dish_receive::AUDIO_LANE_HAPTICS);
    EXPECT_FALSE(speaker.frames[0].concealed);
}

TEST_F(SpeakerQueueTest, AFrameOfASessionThatIsGoneIsTakenButNotDecoded) {
    push(kUnknownHandle, 0);
    push(handle, 0);
    table.close(handle);
    EXPECT_EQ(queue.drain(table, speaker), 2u);
    EXPECT_TRUE(speaker.frames.empty());
}

TEST_F(SpeakerQueueTest, APacketLongerThanTheWireAllowsIsNotQueued) {
    const std::vector<uint8_t> tooLong(dish_wire::AUDIO_WIRE_MAX_OPUS_BYTES + 1, 0x55);
    queue.push(handle, dish_receive::AUDIO_LANE_SPEAKER, dish_wire::AudioFrameHeader{2, 0},
               tooLong.data(), tooLong.size());
    EXPECT_EQ(queue.drain(table, speaker), 0u);
}

TEST_F(SpeakerQueueTest, ABacklogPastTheQueueDropsTheOldestFrames) {
    for (uint16_t seq = 0; seq < dish_receive::SpeakerQueue::FRAMES + 2; seq++) push(handle, seq);
    EXPECT_EQ(queue.droppedFrames(), 2u);
    EXPECT_EQ(queue.drain(table, speaker), dish_receive::SpeakerQueue::FRAMES);
}

// The audio dispatch thread as the JNI layer runs it, fed by a receive thread: every frame of an
// in-order stream comes out (a frame dropped from a backlog comes out concealed), and a stop ends
// the thread once the ring is empty.
TEST_F(SpeakerQueueTest, TheDispatchThreadDecodesAStreamTheReceiveThreadQueues) {
    std::thread dispatch([&] {
        while (queue.waitForWork()) queue.drain(table, speaker);
    });
    std::thread receive([&] {
        for (uint16_t seq = 0; seq < kStreamFrames; seq++) {
            push(handle, seq);
            std::this_thread::sleep_for(std::chrono::milliseconds(2));
        }
    });
    receive.join();
    const auto deadline = Clock::now() + kWaitLimit;
    const size_t expected = kStreamFrames - queue.droppedFrames();
    while (speaker.count() < expected && Clock::now() < deadline) std::this_thread::yield();
    queue.stop();
    dispatch.join();
    EXPECT_GE(speaker.frames.size(), expected);
}
