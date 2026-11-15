import { call } from '../../shared/api/transport';

export interface DeletionDetails {
    title: string;
    revision: number;
    trial: boolean;
    passwordLogin: boolean;
    mailMode: string;
}
export interface DeleteConfirmation {
    revision: number;
    confirmation: string;
    password?: string;
    code?: string;
}
const path = (id: string) => '/preparations/' + encodeURIComponent(id);
export const preparationDeletionApi = {
    details: (id: string) => call<DeletionDetails>(path(id) + '/deletion'),
    proof: (id: string, revision: number) =>
        call<void>(path(id) + '/deletion-proof', 'POST', { revision }),
    delete: (id: string, confirmation: DeleteConfirmation) =>
        call<void>(path(id), 'DELETE', confirmation),
};
