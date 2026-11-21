#define DISCORDPP_IMPLEMENTATION
#include "discordpp.h"
#include <chrono>
#include <cstdint>
#include <future>
#include <iostream>
#include <memory>
#include <regex>
#include <string>
#include <thread>

// Private stdio protocol to the Minecraft mod. No token/secret is requested or stored here.
static std::string hex(const std::string& value) {
    static constexpr char digits[] = "0123456789abcdef";
    std::string out;
    out.reserve(value.size() * 2);
    for (unsigned char c : value) { out += digits[c >> 4]; out += digits[c & 15]; }
    return out;
}

struct Result { bool success; std::string code, redirect; };

int main() {
    try {
        std::string app, challenge, state, pid;
        // Inputs come only from our mod; cap every line before giving anything to the SDK.
        auto line = [](std::string& value) {
            char buffer[129]{};
            if (!std::cin.getline(buffer, sizeof(buffer))) return false;
            value = buffer; return true;
        };
        if (!line(app) || !line(challenge) || !line(state) || !line(pid)
            || !std::regex_match(app, std::regex("[0-9]{1,20}"))
            || !std::regex_match(pid, std::regex("[0-9]{1,10}"))
            || !std::regex_match(challenge, std::regex("[A-Za-z0-9_-]{43}"))
            || !std::regex_match(state, std::regex("[A-Za-z0-9_-]{43}"))) return 2;

        auto client = std::make_shared<discordpp::Client>();
        client->SetApplicationId(std::stoull(app));
        client->SetGameWindowPid(std::stoi(pid));
        discordpp::AuthorizationCodeChallenge pkce{};
        pkce.SetMethod(discordpp::AuthenticationCodeChallengeMethod::S256);
        pkce.SetChallenge(challenge);
        discordpp::AuthorizationArgs args{};
        args.SetClientId(std::stoull(app));
        args.SetScopes(discordpp::Client::GetDefaultPresenceScopes() + " identify");
        args.SetState(state); // The SDK verifies the OAuth callback's state.
        args.SetCodeChallenge(pkce);
        auto completion = std::make_shared<std::promise<Result>>();
        auto result = completion->get_future();
        client->Authorize(args, [completion](discordpp::ClientResult status, std::string code, std::string redirect) {
            completion->set_value({status.Successful(), std::move(code), std::move(redirect)});
        });
        const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(140);
        while (std::chrono::steady_clock::now() < deadline) {
            discordpp::RunCallbacks();
            if (result.wait_for(std::chrono::milliseconds(0)) == std::future_status::ready) {
                auto reply = result.get();
                if (!reply.success || reply.code.empty() || reply.code.size() > 2048 || reply.redirect.size() > 256) return 3;
                std::cout << "NYDS1 OK " << hex(reply.code) << " " << hex(reply.redirect) << std::endl;
                return 0;
            }
            std::this_thread::sleep_for(std::chrono::milliseconds(10));
        }
        return 4;
    } catch (...) {
        // Do not print SDK errors, authorization codes or exception contents.
        return 5;
    }
}
