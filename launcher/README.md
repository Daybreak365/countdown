# 2027 카운트다운 런처 1.2.0

Windows 10/11 x64용 단일 portable EXE입니다. 크리스마스 블록 마을, 큰 2027과 한국 시간 카운트다운 화면을 유지합니다. 플레이어 설정은 메모리 할당 하나입니다.

## 로그인과 실행

공식 PortableMC 5.0.5 Windows CLI를 인증용 보조 프로그램으로 포함합니다. 원본 실행 파일을 변경하지 않고 `auth login --no-browser --output machine`으로 호출합니다. 다른 런처의 ID를 자체 인증 코드에 복사하지 않으며, 자체 Microsoft 앱 ID 입력은 필요하지 않습니다. PortableMC의 실제 Minecraft API 인증 성공 여부는 계정으로 최종 확인해야 합니다.

Microsoft가 제공하는 로그인 페이지를 기존 런처 창의 WebContentsView에 표시합니다. 인증 코드는 같은 창 위쪽에서 복사할 수 있습니다. 로그인 중 팝업은 같은 웹뷰에 표시하고 별도 브라우저 창은 열지 않습니다. 런처는 Microsoft 비밀번호를 읽거나 저장하지 않습니다. 취소 시 인증 보조 프로세스와 웹뷰를 닫습니다.

게임 설치와 실행은 기존 XMCL 엔진이 처리합니다. Minecraft 26.3, Fabric Loader 0.19.5, Java 25로 고정했고, 카운트다운 모드와 Fabric API 0.166.0+26.3을 함께 설치합니다. 게임과 Java는 첫 실행 때 공식 서버에서 다운로드합니다.

플레이를 누르면 로그인과 게임 준비 후 `saebyeok.iptime.org` 서버에 자동 접속합니다. 플레이어 화면에는 버전, 로더와 서버 주소 설정을 표시하지 않습니다.

## 사용

1. 쓰기 가능한 폴더에서 `2027-Launcher-Portable.exe`를 실행합니다.
2. Microsoft 계정 버튼이나 플레이를 눌러 런처 안에서 로그인합니다.
3. Microsoft 화면에서 인증 코드가 필요하면 상단의 복사 버튼을 사용합니다.
4. 플레이를 누르면 필요한 파일을 설치하고 Minecraft를 실행해 `saebyeok.iptime.org` 서버에 자동 접속합니다.
5. 메모리는 설정에서 2~16 GB로 변경합니다.

Minecraft Java Edition을 플레이할 수 있는 계정이 필요합니다. 배포 파일은 EXE 하나이며, 실행 후 EXE 옆 `2027-Launcher-data` 폴더에 게임, 설정과 로그를 저장합니다.

로그인 캐시는 Electron safeStorage로 암호화합니다. 다른 PC 또는 Windows 사용자에서는 다시 로그인해야 합니다. PortableMC가 사용하는 JSON 캐시는 인증 중 사용자 임시 폴더에 잠깐 복호화하고 성공·실패·취소 시 삭제합니다. 비정상 종료 후 남은 임시 캐시는 다음 실행에서 제거합니다. 계정의 이름과 UUID만 화면에 전달하고 액세스 토큰과 갱신 토큰은 전달하지 않습니다.

## 운영자 구성

기본 접속 서버는 `saebyeok.iptime.org`입니다. 변경하려면 `app/server-config.json`의 `serverAddress`를 수정한 뒤 다시 빌드합니다. 별도 포트가 필요하면 `서버 주소:포트` 형식으로 입력합니다.

```json
{
  "minecraft": "26.3",
  "loader": "fabric",
  "loaderVersion": "0.19.5",
  "serverAddress": "saebyeok.iptime.org"
}
```

빈 서버 주소는 게임 실행을 허용하되 자동 접속을 생략합니다. Microsoft의 앱 동의 화면은 인증을 수행하는 기존 앱의 이름을 사용할 수 있습니다. 운영자 고유 앱 이름과 인증 ID로 바꾸려면 해당 앱의 등록과 Minecraft API 권한을 별도로 구성해야 합니다.

## 포함된 모드

`app/server-mods`에 있는 카운트다운 배포 JAR를 그대로 포함합니다. 최상위 폴더의 `test.bat` 또는 `build-launcher.bat`이 `mod/build/libs`의 최신 배포 JAR로 교체하고 `app/assets/submitted-file.json`의 버전·파일 정보·해시도 갱신합니다. 실제 Fabric 모드이며 Java 25와 Minecraft 26.3을 요구합니다. Minecraft 26 계열은 난독화되지 않아 이름 변환 과정이 없습니다.

별도 JAR를 기존 게임에 설치할 때는 Minecraft 26.3, Java 25, Fabric Loader 0.19.5 이상과 Fabric API가 필요합니다. Mod Menu는 선택 사항입니다.

## 빌드

최상위 폴더의 `build-launcher.bat`을 실행하면 최신 모드 반영, 필요한 의존성 설치, 테스트, Portable EXE 빌드를 순서대로 수행합니다. `test.bat`은 두 모드 폴더를 갱신한 뒤 기존 `server/run.bat`으로 서버를 시작하고 개발용 런처를 엽니다. 자세한 사용법은 최상위 `원클릭-사용방법.txt`를 참고하세요.

Node.js 24와 npm을 사용합니다.

```sh
npm ci
npm ci --prefix app
npm test
npm run build:portable
```

결과: `portable-build/2027-Launcher-Portable.exe`

공식 PortableMC 실행 파일은 SHA-256으로 확인한 뒤 패키징합니다. Microsoft 공식 패키지에서 추출한 vcruntime140.dll을 보조 실행 파일 옆에 포함해 별도 Visual C++ 설치를 요구하지 않습니다. EXE 내부에서는 인증 실행 파일과 이 DLL을 ASAR 밖으로 풀어 사용합니다. 실행 시에도 해시를 확인하고, 환경 변수로 들어온 PortableMC의 인증 ID·출력 모드 변경값은 전달하지 않습니다.

Electron 44.5.1 Windows x64를 NSIS portable로 압축합니다. 별도 설치 프로그램이나 관리자 권한은 필요하지 않습니다. `@xmcl/core` 2.16.2의 누락된 utils 진입점은 `app/fix-runtime-deps.cjs`가 npm 설치 후 보완하며, `@xmcl/unzip`은 2.1.2로 고정했습니다.

## 검증 범위

자동 테스트는 모드와 의존성, 고정 구성, 메모리 설정, 다운로드 해시·경로, CLI 파이프 출력과 인증 결과 처리, 세션 갱신, 재로그인, 암호화 저장, 취소와 임시 캐시 정리를 검사합니다. 계정이 게임을 소유하지 않은 경우와 손상된 캐시도 검사합니다. 실제 공식 PortableMC Linux 배포판에서 기본 앱으로 인증 코드가 발급되고 취소 후 임시 파일이 삭제되는 것까지 확인했습니다.

Windows EXE 실기 실행, 실제 Microsoft 계정 로그인 완료와 서버 접속은 검증 전입니다. 별도 브라우저로 넘기는 대체 경로는 포함하지 않습니다.

## 라이선스와 원본

사용자 정의 런처 소스·아이콘·배경과 카운트다운 모드: MIT. PortableMC와 Fabric API: Apache-2.0. PortableMC 라이선스는 `app/vendor/portablemc/LICENSE.txt`에 포함합니다. Microsoft Visual C++ Runtime의 원본과 라이선스 안내는 같은 폴더의 NOTICE.txt에 있습니다. Electron, npm 의존 패키지와 Noto Sans KR의 라이선스는 원본을 유지합니다. Mojang 또는 Microsoft의 공식 제품이 아닙니다.

- PortableMC: https://github.com/theorzr/portablemc
- 공식 Windows 바이너리: https://github.com/theorzr/portablemc/releases/tag/v5.0.5
