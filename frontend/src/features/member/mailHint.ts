export function mailHint(mode?: string) {
    return mode === 'local'
        ? '로컬 테스트 메일은 프로젝트의 .tools/account-mail 폴더에 저장됩니다.'
        : mode === 'smtp'
          ? '등록한 이메일에서 링크를 확인하세요.'
          : '이 환경에서는 발송용 메일 서버가 설정되지 않았습니다.';
}
