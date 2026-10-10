import { Button, PasswordInput, Stack } from '@mantine/core';
import { useForm } from '@mantine/form';
import { useNavigate, useParams } from 'react-router-dom';
import { z } from 'zod';
import { useAuthConfirmReset } from '../../api/generated/auth-controller/auth-controller';
import { notifyError, notifySuccess } from '../../lib/errors';
import { zodValidate } from '../../lib/zodForm';
import { AuthShell } from './AuthShell';

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
