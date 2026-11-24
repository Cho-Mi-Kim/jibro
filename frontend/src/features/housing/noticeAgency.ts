import lhCi from '../../shared/assets/lh-ci.svg';

interface AgencyIdentity {
    name: string;
    fullName?: string;
    logo?: string;
}

const agencies = [
    {
        name: 'LH',
        fullName: '한국토지주택공사',
        logo: lhCi,
        aliases: ['LH(한국토지주택공사)', '한국토지주택공사(LH)'],
    },
];

export function noticeAgency(value?: string | null): AgencyIdentity {
    const name = value?.trim() || '운영기관 확인 필요';
    const key = name.replace(/\s+/g, '').toUpperCase();
    const known = agencies.find((agency) =>
        [agency.name, agency.fullName, ...agency.aliases].some(
            (alias) => alias.replace(/\s+/g, '').toUpperCase() === key,
        ),
    );
    return known ?? { name };
}
