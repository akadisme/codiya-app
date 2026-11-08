# 코디야 앱

- Flutter 앱 (안드로이드 먼저)
- 패키지: `kr.codiya.app`
- 사이트: https://codiya.kr · 앱 API: https://codiya.kr/cdapi/app/index.php
- `main`에 올리면 GitHub Actions가 APK를 자동으로 만들어 `builds` 브랜치에 보관해요
  - 홈페이지 다운로드 페이지(https://codiya.kr/app/)가 이 파일을 내려줘요
- 공개 저장소라서 `google-services.json` · `upload.keystore` 는 저장소에 넣지 않아요
  - GitHub 비밀값: `GOOGLE_SERVICES_JSON`, `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`
