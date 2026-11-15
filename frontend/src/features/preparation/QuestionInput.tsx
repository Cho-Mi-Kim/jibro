import type { Answer, Question } from '../housing/types';
import {
    answerOptions,
    inputDescription,
    questionKind,
    validAnswer,
} from '../housing/questions';
import { cx } from '../../shared/styles/classes';
import styles from './QuestionInput.module.css';

export function QuestionInput({
    question,
    value,
    disabled,
    onChange,
    onRepeatChoice,
}: {
    question: Question;
    value: Answer | undefined;
    disabled: boolean;
    onChange: (value: Answer) => void;
    onRepeatChoice: () => void;
}) {
    const kind = questionKind(question);
    const c = question.constraints;
    if (kind === 'SINGLE')
        return answerOptions(question).map((option) => (
            <label className={cx('choice')} key={option.value}>
                <input
                    type="radio"
                    name={question.id}
                    checked={value === option.value}
                    disabled={disabled}
                    onClick={() => {
                        if (value === option.value) onRepeatChoice();
                    }}
                    onChange={() => onChange(option.value)}
                />
                {option.label}
            </label>
        ));
    if (kind === 'MULTIPLE') {
        const selected = Array.isArray(value) ? value : [];
        const selectedChoices = selected.filter((choice) => choice !== 'NONE');
        const max = c?.maxSelections ?? answerOptions(question).length - 1;
        return (
            <>
                <p className={styles.hint}>
                    해당하는 항목을 고른 뒤 다음을 눌러 주세요. 최대 {max}개
                    선택할 수 있어요.
                </p>
                {answerOptions(question).map((option) => {
                    const unknown = option.value === 'UNKNOWN';
                    const none = option.value === 'NONE';
                    const checked = unknown
                        ? value === 'UNKNOWN'
                        : selected.includes(option.value);
                    return (
                        <label className={cx('choice')} key={option.value}>
                            <input
                                type="checkbox"
                                checked={checked}
                                disabled={
                                    disabled ||
                                    (!unknown &&
                                        !none &&
                                        !checked &&
                                        selectedChoices.length >= max)
                                }
                                onChange={() => {
                                    if (unknown)
                                        onChange(checked ? [] : 'UNKNOWN');
                                    else if (none)
                                        onChange(
                                            checked
                                                ? selectedChoices
                                                : ['NONE'],
                                        );
                                    else
                                        onChange(
                                            checked
                                                ? selectedChoices.filter(
                                                      (v) => v !== option.value,
                                                  )
                                                : [
                                                      ...selectedChoices,
                                                      option.value,
                                                  ],
                                        );
                                }}
                            />
                            {option.label}
                        </label>
                    );
                })}
            </>
        );
    }
    const unknown = value === 'UNKNOWN';
    return (
        <div className={styles.scalar}>
            <label>
                {kind === 'NUMBER' ? '숫자 입력' : '날짜 입력'}
                {kind === 'NUMBER' ? (
                    <input
                        type="number"
                        inputMode={
                            (c?.step ?? 1) % 1 === 0 ? 'numeric' : 'decimal'
                        }
                        value={
                            unknown || Array.isArray(value) ? '' : (value ?? '')
                        }
                        min={c?.min ?? undefined}
                        max={c?.max ?? undefined}
                        step={c?.step ?? 1}
                        disabled={disabled || unknown}
                        aria-describedby={question.id + '-input-help'}
                        onChange={(event) =>
                            onChange(
                                event.target.value === ''
                                    ? ''
                                    : Number(event.target.value),
                            )
                        }
                    />
                ) : (
                    <input
                        type="date"
                        value={
                            unknown || typeof value !== 'string' ? '' : value
                        }
                        min={c?.minDate ?? undefined}
                        max={c?.maxDate ?? undefined}
                        disabled={disabled || unknown}
                        aria-describedby={question.id + '-input-help'}
                        onChange={(event) => onChange(event.target.value)}
                    />
                )}
            </label>
            <p id={question.id + '-input-help'} className={styles.hint}>
                {inputDescription(question)}
                <br />
                입력한 뒤 다음을 눌러 주세요. 입력 범위는 자격 판정이 아니에요.
            </p>
            {value !== undefined &&
                !unknown &&
                !validAnswer(question, value) && (
                    <p role="status" className={styles.hint}>
                        안내된 형식과 범위로 입력해 주세요.
                    </p>
                )}
            <label className={cx('choice')}>
                <input
                    type="checkbox"
                    checked={unknown}
                    disabled={disabled}
                    onChange={() => onChange(unknown ? '' : 'UNKNOWN')}
                />
                잘 모르겠어요
            </label>
        </div>
    );
}
