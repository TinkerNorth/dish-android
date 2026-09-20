// SPDX-License-Identifier: LGPL-3.0-or-later

#pragma once

#include <atomic>
#include <functional>
#include <mutex>
#include <thread>

namespace dish {

class HeartbeatThread {
  public:
    HeartbeatThread() = default;
    ~HeartbeatThread() { stop(); }
    HeartbeatThread(const HeartbeatThread&) = delete;
    HeartbeatThread& operator=(const HeartbeatThread&) = delete;

    bool start(std::function<void()> body) {
        std::lock_guard<std::mutex> lock(mtx_);
        if (running_.load(std::memory_order_acquire)) return false;
        if (thread_.joinable()) {
            if (thread_.get_id() == std::this_thread::get_id()) return false;
            thread_.join();
        }
        running_.store(true, std::memory_order_release);
        thread_ = std::thread(std::move(body));
        return true;
    }

    void stop() {
        std::lock_guard<std::mutex> lock(mtx_);
        running_.store(false, std::memory_order_release);
        if (!thread_.joinable()) return;
        if (thread_.get_id() == std::this_thread::get_id()) {
            thread_.detach();
            return;
        }
        thread_.join();
    }

    bool running() const { return running_.load(std::memory_order_relaxed); }

  private:
    std::mutex mtx_;
    std::thread thread_;
    std::atomic<bool> running_{false};
};

} // namespace dish
