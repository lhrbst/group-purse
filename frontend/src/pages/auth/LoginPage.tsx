import { Anchor, Button, PasswordInput, Stack, TextInput, Text } from '@mantine/core';
import { useForm } from '@mantine/form';
import { Link, Navigate, useNavigate, useSearchParams } from 'react-router-dom';
import { z } from 'zod';
import { useAuthLogin } from '../../api/generated/auth-controller/auth-controller';
import { useAuth } from '../../auth/AuthProvider';
import { notifyError } from '../../lib/errors';
import { zodValidate } from '../../lib/zodForm';
import { AuthShell } from './AuthShell';

const schema = z.object({
  email: z.string().min(1, 'Bitte E-Mail eingeben'),
  password: z.string().min(1, 'Bitte Passwort eingeben'),
});

/** Nur interne Pfade zulassen, damit "next" nicht als offene Weiterleitung missbraucht wird. */
export function safeNext(next: string | null): string {
  return next && next.startsWith('/') && !next.startsWith('//') ? next : '/';
}

export function LoginPage() {
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const next = safeNext(params.get('next'));
  const { me, reset } = useAuth();
  const login = useAuthLogin();
  const form = useForm({ initialValues: { email: '', password: '' }, validate: zodValidate(schema) });

  if (me) return <Navigate to={next} replace />;

  return (
    <AuthShell title="Anmelden">
      <form
        onSubmit={form.onSubmit(async (values) => {
          try {
            await login.mutateAsync({ data: values });
            await reset();
            navigate(next, { replace: true });
          } catch (e) {
            notifyError(e, 'Anmeldung fehlgeschlagen');
          }
        })}
      >
        <Stack>
          <TextInput label="E-Mail" type="email" autoComplete="username" {...form.getInputProps('email')} />
          <PasswordInput label="Passwort" autoComplete="current-password" {...form.getInputProps('password')} />
          <Button type="submit" loading={login.isPending}>
            Anmelden
          </Button>
          <Anchor component={Link} to="/passwort-vergessen" size="sm">
            Passwort vergessen?
          </Anchor>
          <Text size="sm">
            Neu in einer Wohngruppe?{' '}
            <Anchor component={Link} to="/registrieren">
              Mit Beitrittscode registrieren
            </Anchor>
          </Text>
        </Stack>
      </form>
    </AuthShell>
  );
}
