import { Alert, Anchor, Button, Checkbox, PasswordInput, Stack, Text, TextInput } from '@mantine/core';
import { useForm } from '@mantine/form';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { z } from 'zod';
import { useAdminInfo, useAdminRedeem } from '../../api/generated/admin-controller/admin-controller';
import { useAuth } from '../../auth/AuthProvider';
import { PrivacyNotice } from '../../components/PrivacyNotice';
import { LoadError, Loading } from '../../components/Loading';
import { notifyError } from '../../lib/errors';
import { zodValidate } from '../../lib/zodForm';
import { AuthShell } from './AuthShell';

const schema = z.object({
  name: z.string().trim().min(1, 'Bitte Namen eingeben'),
  password: z.string().min(10, 'Das Passwort braucht mindestens 10 Zeichen'),
  datenschutz: z.boolean().refine((v) => v, 'Bitte den Datenschutzhinweis bestätigen'),
});

export function InvitationPage() {
  const { token = '' } = useParams();
  const navigate = useNavigate();
  const { me, reset } = useAuth();
  const info = useAdminInfo(token, { query: { retry: false } });
  const redeem = useAdminRedeem();
  const form = useForm({ initialValues: { name: '', password: '', datenschutz: false }, validate: zodValidate(schema) });

  if (info.isLoading) return <AuthShell title="Einladung"><Loading /></AuthShell>;
  if (info.isError || info.data?.status !== 200) {
    return (
      <AuthShell title="Einladung">
        <LoadError error={info.error ?? new Error('Die Einladung ist ungültig oder abgelaufen.')} />
      </AuthShell>
    );
  }
  const invitation = info.data.data;

  async function accept(values: { name?: string; password?: string; datenschutz?: boolean }) {
    try {
      await redeem.mutateAsync({
        token,
        data: { name: values.name, password: values.password, datenschutzZugestimmt: values.datenschutz },
      });
      await reset();
      navigate('/', { replace: true });
    } catch (e) {
      notifyError(e, 'Einladung konnte nicht angenommen werden');
    }
  }

  return (
    <AuthShell title="Einladung als Kassenverwalter" subtitle={invitation.wohngruppeName}>
      <Stack>
        <Text size="sm">
          Du wurdest für <b>{invitation.wohngruppeName}</b> eingeladen ({invitation.email}).
        </Text>
        {invitation.kontoExistiert ? (
          me && me.email?.toLowerCase() === invitation.email?.toLowerCase() ? (
            <Button loading={redeem.isPending} onClick={() => accept({})}>
              Einladung annehmen
            </Button>
          ) : (
            <>
              <Alert color="blue">Für diese Adresse gibt es schon ein Konto. Bitte melde dich damit an, um die Einladung anzunehmen.</Alert>
              <Button component={Link} to={`/login?next=${encodeURIComponent(`/einladung/${token}`)}`}>
                Anmelden
              </Button>
            </>
          )
        ) : (
          <form onSubmit={form.onSubmit(accept)}>
            <Stack>
              <TextInput label="Name" autoComplete="name" {...form.getInputProps('name')} />
              <PasswordInput label="Passwort" description="Mindestens 10 Zeichen" autoComplete="new-password" {...form.getInputProps('password')} />
              <Checkbox label="Ich habe den Datenschutzhinweis gelesen und stimme zu." {...form.getInputProps('datenschutz', { type: 'checkbox' })} />
              <PrivacyNotice />
              <Button type="submit" loading={redeem.isPending}>
                Konto anlegen und annehmen
              </Button>
            </Stack>
          </form>
        )}
        <Anchor component={Link} to="/login" size="sm">
          Zur Anmeldung
        </Anchor>
      </Stack>
    </AuthShell>
  );
}
