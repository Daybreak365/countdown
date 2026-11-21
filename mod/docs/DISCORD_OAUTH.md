# Discord 웹 OAuth 연동 (1.2.19)

서버와 클라이언트에 `newyear-countdown-1.2.19.jar`를 설치합니다. 접속 시 연동 화면 또는 `/discord link`에서 **브라우저로 Discord 연결**을 누르면 기본 브라우저에 Discord 로그인·동의 화면이 열립니다. 승인 후 게임으로 돌아오면 서버가 프로필을 확인하고 연동 완료 화면을 표시합니다. Social SDK, C++ 실행 파일, DLL은 필요하지 않습니다.

**버튼이 비활성화되어 있다면:** 기존 봇 토큰만으로 웹 인증이 켜지지는 않습니다. 이 작업 폴더에서는 `server/config/newyearcountdown-discord.json`의 `oauthClientId`와 `oauthClientSecret`에 같은 Discord 애플리케이션의 OAuth2 값을 입력하고 아래 Redirect를 개발자 포털에 등록하세요. Secret은 서버 파일에서만 입력합니다. 1.2.19부터 화면과 `/discord status`에 누락된 항목이 표시되며 `/discord reload`로 열려 있는 화면도 갱신됩니다. 버튼 활성화는 로컬 설정 검사이며, 실제 로그인 성공 여부는 Discord 인증을 완료해야 확인됩니다.

## 서버 설정

1. 기존 봇과 같은 [Discord 애플리케이션](https://discord.com/developers/applications)의 **OAuth2 → Redirects**에 아래 주소를 정확히 등록하고 저장합니다.

   ```text
   http://127.0.0.1:53137/callback
   ```

2. Minecraft 서버 프로세스에 다음 환경 변수를 설정합니다. Application ID는 OAuth2의 Client ID와 같습니다. Client Secret은 OAuth2에서 발급하며 봇 토큰과 다릅니다.

   ```text
   DISCORD_CLIENT_ID=애플리케이션_ID
   DISCORD_CLIENT_SECRET=OAuth2_Client_Secret
   DISCORD_BOT_TOKEN=기존_봇_토큰
   ```

3. 서버의 `config/newyearcountdown-discord.json`에서 기존 필드를 유지하고 다음 필드를 설정합니다. 업데이트 후 설정을 로드하면 새 필드가 기본값으로 추가됩니다.

   ```json
   {
     "oauthEnabled": true,
     "oauthClientId": "",
     "oauthClientSecret": "",
     "oauthRedirectUri": "http://127.0.0.1:53137/callback"
   }
   ```

   환경 변수 대신 `oauthClientId`, `oauthClientSecret`을 **서버 설정 파일에만** 넣어도 됩니다. 환경 변수가 우선하며, 새 필드가 비어 있으면 기존 `socialSdkClientId`, `socialSdkClientSecret`도 재사용합니다. Public Client나 Social SDK 활성화는 필요하지 않습니다. `socialSdkEnabled=false`여도 웹 연동은 동작합니다.

4. 파일 변경은 `/discord reload`, 환경 변수 변경은 서버 프로세스 재시작으로 반영합니다. OP의 `/discord status`에 **웹 OAuth 인증: 서버 설정 완료**가 표시되는지 확인합니다. 이 표시는 로컬 설정 검사 결과이며, Developer Portal에 Redirect가 실제 등록되었는지는 브라우저 인증 때 확인됩니다.

계정 확인만 할 때는 봇 토큰이 없어도 웹 연동을 사용할 수 있습니다. 채팅 중계와 기존 `/연동 코드:...` 명령에는 봇이 필요합니다. 봇이 실행 중이지 않으면 새 연동 화면의 코드 버튼은 비활성화됩니다.

## 플레이어 사용법

- 게임 연동 화면에서 **브라우저로 Discord 연결**을 누르고 연결할 Discord 계정인지 확인한 뒤 승인합니다.
- 브라우저가 자동으로 열리지 않으면 **인증 링크 복사**를 눌러 **게임이 실행 중인 같은 PC**의 브라우저에 붙여 넣습니다. 다른 PC나 휴대전화에서는 완료할 수 없습니다.
- 브라우저의 승인 안내 후 게임에서 최종 연동 결과를 확인합니다. 인증 취소·거절·시간 초과 후에는 연결 버튼으로 다시 시작할 수 있습니다.
- **코드로 연동**은 기존 방식입니다. 코드 화면에서 **인증 화면으로 돌아가기**로 웹 인증을 다시 선택할 수 있습니다.
- 기존 연결을 바꾸려면 먼저 `/discord unlink` 후 다시 연결합니다. 다른 Minecraft 계정에 연결된 Discord 계정은 기존 연결을 먼저 해제해야 합니다.

## 연결 주소와 오류 해결

콜백 수신기는 인증 중에만 **플레이어 PC의 `127.0.0.1:53137`**에서 열립니다. Minecraft 서버에 웹 포트 개방·포트 포워딩·도메인·HTTPS 서버를 추가할 필요가 없습니다. 외부 네트워크 인터페이스에는 바인딩하지 않습니다. 취소·완료·화면 종료·시간 초과 때 수신기를 닫습니다.

- **Redirect URI 오류**: Developer Portal 주소와 `oauthRedirectUri`의 포트·경로까지 일치시킵니다. 기존 SDK 주소 `http://127.0.0.1/callback`만 등록했다면 웹 주소를 추가해야 합니다.
- **포트를 열지 못함**: 같은 PC의 다른 Minecraft 인증 창을 닫거나 다른 프로그램의 포트 사용을 확인합니다. 포트를 변경해야 하면 서버의 `oauthRedirectUri`와 Developer Portal에 같은 `http://127.0.0.1:포트/callback` 주소를 등록합니다. 포트 범위는 1024~65535입니다.
- **승인 후 게임 인증 실패**: Client ID와 Client Secret이 같은 애플리케이션의 것인지, Minecraft 서버에서 Discord API에 접속할 수 있는지 확인합니다.
- **설정 변경 중인 인증**: `/discord reload`는 진행 중 세션을 무효화합니다. 게임에서 취소한 다음 새 인증을 시작합니다.
- 기존 버전과의 SDK·코드 패킷 호환은 유지하지만, 웹 연동에는 서버와 클라이언트 모두 1.2.17 이상이 필요합니다.

## 구현 및 검증

브라우저 요청 범위는 `identify`이며 Authorization Code + PKCE S256을 사용합니다. 클라이언트에서 임의의 `state`를 만들고 콜백의 state·발급자(`iss`, 있는 경우)·단일 파라미터·경로를 검사합니다. PKCE 검증값과 Client Secret은 Minecraft 서버에만 있습니다. 서버 세션은 플레이어 UUID와 원래 연결에 묶이며, 한 번만 사용할 수 있고 3분 후 만료됩니다. 클라이언트 수신기는 165초 후 닫힙니다. 웹 Redirect는 등록된 주소와 정확히 일치해야 합니다.

서버는 코드를 교환해 `/users/@me`에서 받은 프로필만 기존 연동 기록에 저장합니다. OAuth 토큰과 Client Secret은 클라이언트로 보내지 않으며, 토큰을 파일에 저장하지 않습니다. 브라우저 안내에는 승인 코드·토큰·외부 오류 내용을 출력하지 않습니다. 웹 연동과 기존 코드 연동이 겹치면 이미 완료된 연동을 뒤늦은 OAuth 응답으로 덮어쓰지 않습니다.

검증 명령:

```powershell
./tools/verify-runtime.ps1 -Test
./tools/package-verified.ps1
```

자동 검증은 로컬 HTTP 콜백·모의 Discord API 및 Java 테스트입니다. 실제 Discord 로그인·실제 인게임 화면은 위 애플리케이션 설정을 완료한 환경에서 확인해야 합니다.

공식 프로토콜 참고: [Discord OAuth2](https://docs.discord.com/developers/topics/oauth2). 기존 SDK를 계속 사용할 경우 [Social SDK 안내](DISCORD_SOCIAL.md)를 참고합니다.
