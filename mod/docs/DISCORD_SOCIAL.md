# Discord 프로필 채팅과 Social SDK 연결

1.2.17부터 SDK 없이 **브라우저 OAuth 연동**을 사용할 수 있습니다. 일반 계정 연동은 [웹 OAuth 설정 안내](DISCORD_OAUTH.md)를 따릅니다. 아래 네이티브 SDK 절차는 기존 SDK 인증창을 사용할 때만 필요합니다.

## 프로필 채팅

서버와 클라이언트를 1.2.16 이상으로 맞춥니다. 봇이 사용하는 채널에 **웹훅 관리** 권한을 추가하면 `Minecraft Chat`이라는 봇 소유 웹훅을 하나 만들어 재사용합니다. 플레이어마다 웹훅을 만들지 않습니다. 기존 봇이 만든 같은 이름의 웹훅만 재사용하며 다른 관리자의 웹훅은 건드리지 않습니다.

게임에서 보낸 채팅은 연동된 Discord 표시 이름과 @아이디, 프로필 사진으로 표시됩니다. 웹훅 메시지이므로 Discord의 **앱** 표시는 남고, 실제 사용자가 Discord 앱에서 직접 보낸 메시지는 아닙니다. 접속·퇴장 등 시스템 알림은 서버 봇 이름으로 표시됩니다. 모든 메시지는 `allowed_mentions.parse=[]`로 전송해 게임에서 `@everyone` 등을 입력해도 Discord 알림을 울리지 않습니다. 수신 시 봇/웹훅 메시지는 무시해 중계 루프를 막습니다.

`relayUsingLinkedProfiles=false`로 기존 봇 중계 형식으로 돌아갈 수 있습니다. 웹훅 권한 오류 또는 삭제 시에는 1분 동안 재생성을 재시도하지 않고 봇 중계로 전달합니다.

## Social SDK 실행에 필요한 파일

이 저장소에는 공식 C++ Social SDK를 사용하는 호스트 소스(`native/discord-social/main.cpp`), CMake 빌드, Minecraft 클라이언트 연결 코드와 서버 인증 코드가 있습니다. **공식 SDK 헤더·DLL과 빌드된 호스트는 별도로 필요합니다. SDK 없이 빌드한 jar에서는 인증창 버튼이 비활성화되며 코드 연동을 사용할 수 있습니다.**

1. 기존 봇과 **같은 애플리케이션**의 [Discord Developer Portal](https://discord.com/developers/applications)에서 Social SDK 설정을 마치고 **Discord Social SDK → Downloads → C++** SDK를 받습니다.
2. SDK를 압축 해제합니다. `include/discordpp.h`, `lib/release/discord_partner_sdk.lib`, `bin/release/discord_partner_sdk.dll`이 있는 루트를 사용합니다.
3. CMake 3.21 이상과 Visual Studio C++ 빌드 도구를 설치한 개발자 PowerShell에서 실행합니다.

```powershell
./tools/build-discord-social.ps1 -SdkRoot 'C:\path\to\DiscordSocialSdk' -Bundle
./tools/verify-runtime.ps1 -Test
./tools/package-verified.ps1
```

`-Bundle`은 호스트와 DLL을 모드 리소스에 넣습니다. 해당 jar를 플레이어에게 배포하면 클라이언트가 자기 PC에 실행 파일을 준비하고, 연결 버튼을 눌렀을 때만 실행합니다. `-Architecture arm64`는 Windows ARM64용입니다. 플랫폼마다 해당 아키텍처의 SDK 파일이 필요합니다. SDK의 배포 약관과 포함된 라이선스/고지 파일을 함께 따릅니다.

직접 설치할 때는 호스트와 SDK DLL을 플레이어의 `config/newyearcountdown-social-sdk/windows-x86_64/`에 나란히 둡니다. 서버에는 네이티브 SDK가 필요 없습니다. 실행 파일 경로를 서버 패킷에서 받아 실행하지 않습니다.

Linux/macOS는 같은 CMake 프로젝트를 플랫폼에 맞춰 빌드하고, 실행 파일과 `.so`/`.dylib`를 `linux-x86_64`, `linux-arm64`, `macos-x86_64`, `macos-arm64` 디렉터리에 넣는 구조입니다. 지원 플랫폼·SDK 라이브러리 경로는 다운로드한 공식 SDK를 확인해야 합니다. 현재 이 환경에서는 네이티브 SDK 빌드와 플랫폼별 인증창을 검증하지 못했습니다.

## 서버 OAuth 설정

Developer Portal의 OAuth2 Redirects에 **`http://127.0.0.1/callback`**을 등록합니다. SDK가 플레이어 PC에서 콜백을 처리하므로 Minecraft 서버에 공개 웹 서버나 포트를 추가하지 않습니다. Public Client는 켜지 않고 서버의 Client Secret으로 인증 코드를 교환합니다.

서버 환경 변수:

```text
DISCORD_CLIENT_ID=기존 봇 애플리케이션의 Application ID
DISCORD_CLIENT_SECRET=그 애플리케이션의 OAuth2 Client Secret
DISCORD_BOT_TOKEN=기존 봇 토큰
```

서버의 `config/newyearcountdown-discord.json`에서 아래 필드를 사용할 수도 있습니다. 환경 변수가 우선합니다. 비밀 키를 플레이어용 모드·클라이언트 설정에 넣지 않습니다.

```json
{
  "socialSdkEnabled": true,
  "socialSdkClientId": "APPLICATION_ID",
  "socialSdkClientSecret": "",
  "socialSdkRedirectUri": "http://127.0.0.1/callback",
  "relayUsingLinkedProfiles": true
}
```

위 예시는 추가 필드만 보여 줍니다. 기존 `token`, `channelId` 등의 설정을 유지하고 병합합니다. 파일 변경 후 `/discord reload`, 환경 변수 변경 후 서버 프로세스를 다시 시작합니다. OP의 `/discord status`에서 서버 SDK 설정 여부를 확인할 수 있습니다.

## 플레이어 흐름과 검증

- 접속 시 또는 `/discord link` → **Discord로 연결** → Discord 앱/오버레이에서 승인 → 계정 확인 → 프로필 표시 → 접속 계속. Discord 앱을 사용할 수 없으면 SDK가 브라우저로 진행합니다. 오버레이 사용 여부는 Discord 및 게임 환경에 따라 달라집니다.
- **코드로 연동**은 기존 6자리 명령 방식을 엽니다. 인증 취소, 구성 요소 없음, 인증 실패 시에도 사용할 수 있습니다.
- SDK에는 `openid sdk.social_layer_presence identify` 범위를 요청합니다. 서버는 일회용 PKCE 검증값을 보관하고 클라이언트에는 공개 챌린지만 보냅니다. 승인 코드를 원래 플레이어·원래 연결에 묶어 서버에서 교환하고 `/users/@me`를 조회합니다. 클라이언트가 제출한 사용자 이름이나 Discord ID로 연동하지 않습니다.
- 완료 응답 재사용, 다른 접속에서의 코드 사용, 만료된 요청, 연결 종료/취소 후의 늦은 완료를 거부합니다. 다른 플레이어에게 이미 연결된 Discord 계정은 먼저 기존 연동을 해제해야 합니다. 저장 실패 시 메모리상의 연결도 되돌립니다.
- 토큰·Client Secret은 클라이언트로 보내지 않으며 사용자 OAuth 토큰은 파일에 저장하지 않습니다. 이 기능은 계정 확인만 수행하고 SDK 친구 목록·음성 채팅·지속 접속은 추가하지 않습니다.

검증은 로컬 HTTP 모의 서버와 Java 테스트로 수행합니다. 실제 Discord 채널로 테스트 메시지를 보내지 않습니다. 실제 인증창 확인은 공식 SDK를 포함한 빌드와 해당 애플리케이션 설정이 준비된 PC에서 진행해야 합니다.

공식 참고: [Social SDK C++ 시작하기](https://docs.discord.com/developers/discord-social-sdk/getting-started/using-c%2B%2B), [계정 연결](https://docs.discord.com/developers/discord-social-sdk/development-guides/account-linking-with-discord), [웹훅 API](https://docs.discord.com/developers/resources/webhook).
