import { Alert, Anchor, Button, PasswordInput, Stack, TextInput } from '@mantine/core';
import { useForm } from '@mantine/form';
import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { z } from 'zod';
import { useAuthConfirmReset, useAuthRequestReset } from '../../api/generated/auth-controller/auth-controller';
import { notifyError, notifySuccess } from '../../lib/errors';
import { zodValidate } from '../../lib/zodForm';
import { AuthShell } from './AuthShell';

export function ForgotPasswordPage() {
  const request = useAuthRequestReset();
  const [sent, setSent] = useState(false);
  const form = useForm({
    initialValues: { email: '' },
    validate: zodValidate(z.object({ email: z.string().trim().min(1, 'Bitte E-Mail eingeben').email('Keine gültige E-Mail-Adresse') })),
  });

  return (
    <AuthShell title="Passwort zurücksetzen">
      {sent ? (
        <Stack>
          <Alert color="green">Wenn die Adresse bei uns bekannt ist, haben wir dir einen Link zum Zurücksetzen geschickt. Er ist eine Stunde gültig.</Alert>
          <Anchor component={Link} to="/login" size="sm">
            Zurück zur Anmeldung
          </Anchor>
        </Stack>
      ) : (
        <form
          onSubmit={form.onSubmit(async (v) => {
            try {
              await request.mutateAsync({ data: { email: v.email } });
              setSent(true);
            } catch (e) {
              notifyError(e);
            }
          })}
        >
          <Stack>
            <TextInput label="E-Mail" type="email" autoComplete="username" {...form.getInputProps('email')} />
            <Button type="submit" loading={request.isPending}>
              Link anfordern
            </Button>
            <Anchor component={Link} to="/login" size="sm">
              Zurück zur Anmeldung
            </Anchor>
          </Stack>
        </form>
      )}
    </AuthShell>
  );
}

export function ResetPasswordPage() {
  const { token = '' } = useParams();
  const navigate = useNavigate();
  const confirm = useAuthConfirmReset();
  const form = useForm({
    initialValues: { password: '', repeat: '' },
    validate: zodValidate(
      z
        .object({ password: z.string().min(10, 'Das Passwort braucht mindestens 10 Zeichen'), repeat: z.string() })
        .refine((v) => v.password === v.repeat, { path: ['repeat'], message: 'Die Passwörter stimmen nicht überein' }),
    ),
  });

  return (
    <AuthShell title="Neues Passwort setzen">
      <form
        onSubmit={form.onSubmit(async (v) => {
          try {
            await confirm.mutateAsync({ data: { token, newPassword: v.password } });
            notifySuccess('Passwort geändert. Du kannst dich jetzt anmelden.');
            navigate('/login', { replace: true });
          } catch (e) {
            notifyError(e);
          }
        })}
      >
        <Stack>
          <PasswordInput label="Neues Passwort" autoComplete="new-password" {...form.getInputProps('password')} />
          <PasswordInput label="Wiederholen" autoComplete="new-password" {...form.getInputProps('repeat')} />
          <Button type="submit" loading={confirm.isPending}>
            Passwort speichern
          </Button>
        </Stack>
      </form>
    </AuthShell>
  );
}
