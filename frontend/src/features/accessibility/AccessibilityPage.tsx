import { Button, Heading } from '../../shared/ui/ui';
import { useAccessibility } from './context';
import {
    defaultPreferences,
    type AccessibilityPreferences,
} from './preferences';
import s from './Accessibility.module.css';

export function AccessibilityPage() {
    const { preferences, persistent, update } = useAccessibility();
    function change<K extends keyof AccessibilityPreferences>(
        key: K,
        value: AccessibilityPreferences[K],
    ) {
        update({ ...preferences, [key]: value });
    }
    return (
        <>
            <Heading title="화면·접근성 설정" backTo="/my" />
            <div className={s.page}>
                <section className={s.panel} aria-label="화면 표시 설정">
                    <fieldset className={s.group}>
                        <legend>화면 모드</legend>
                        <div className={s.sizes}>
                            {(
                                [
                                    ['light', '라이트 모드'],
                                    ['dark', '다크 모드'],
                                ] as const
                            ).map(([value, label]) => (
                                <label key={value} className={s.option}>
                                    <input
                                        type="radio"
                                        name="theme"
                                        value={value}
                                        checked={preferences.theme === value}
                                        onChange={() => change('theme', value)}
                                    />
                                    <span>{label}</span>
                                </label>
                            ))}
                        </div>
                    </fieldset>
                    <fieldset className={s.group}>
                        <legend>글자 크기</legend>
                        <div className={s.sizes}>
                            {(
                                [
                                    ['default', '기본'],
                                    ['large', '크게'],
                                    ['extra', '아주 크게'],
                                ] as const
                            ).map(([value, label]) => (
                                <label key={value} className={s.option}>
                                    <input
                                        type="radio"
                                        name="text-size"
                                        value={value}
                                        checked={preferences.textSize === value}
                                        onChange={() =>
                                            change('textSize', value)
                                        }
                                    />
                                    <span>{label}</span>
                                </label>
                            ))}
                        </div>
                    </fieldset>
                    <label className={s.toggle}>
                        <span>
                            <strong>대비 강화</strong>
                            <small>
                                글자와 구분선을 더 또렷하게 표시합니다.
                            </small>
                        </span>
                        <input
                            type="checkbox"
                            checked={preferences.highContrast}
                            onChange={(e) =>
                                change('highContrast', e.target.checked)
                            }
                        />
                    </label>
                    <label className={s.toggle}>
                        <span>
                            <strong>화면 움직임 줄이기</strong>
                            <small>
                                펼침 효과와 자동 스크롤의 움직임을 줄입니다.
                                기기의 동작 줄이기 설정도 항상 따릅니다.
                            </small>
                        </span>
                        <input
                            type="checkbox"
                            checked={preferences.reduceMotion}
                            onChange={(e) =>
                                change('reduceMotion', e.target.checked)
                            }
                        />
                    </label>
                </section>
                {!persistent && (
                    <p role="status" className={s.description}>
                        브라우저 저장을 사용할 수 없어 이번 화면에만 적용됩니다.
                    </p>
                )}
                <Button
                    className="secondary"
                    onClick={() => update(defaultPreferences)}
                >
                    기본 설정으로 되돌리기
                </Button>
            </div>
        </>
    );
}
