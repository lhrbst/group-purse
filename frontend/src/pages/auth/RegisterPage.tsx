import { Alert, Anchor, Button, Checkbox, PasswordInput, Stack, TextInput, Text } from '@mantine/core';
import { useForm } from '@mantine/form';
import { Link, useNavigate } from 'react-router-dom';
import { z } from 'zod';
import { useAuthRegister } from '../../api/generated/auth-controller/auth-controller';
import { PrivacyNotice } from '../../components/PrivacyNotice';
import { useAuth } from '../../auth/AuthProvider';
import { notifyError } from '../../lib/errors';
import { zodValidate } from '../../lib/zodForm';
import { AuthShell } from './AuthShell';

const schema = z.object({
  name: z.string().trim().min(1, 'Bitte Namen eingeben'),
  email: z.string().trim().min(1, 'Bitte E-Mail eingeben').email('Keine gültige E-Mail-Adresse'),
  password: z.string().min(10, 'Das Passwort braucht mindestens 10 Zeichen'),
  beitrittscode: z.string().trim().min(1, 'Bitte den Beitrittscode eingeben'),
  datenschutz: z.boolean().refine((v) => v, 'Bitte den Datenschutzhinweis bestätigen'),
});

export function RegisterPage() {
  const navigate = useNavigate();
  const { reset } = useAuth();
  const register = useAuthRegister();
  const form = useForm({
    initialValues: { name: '', email: '', password: '', beitrittscode: '', datenschutz: false },
    validate: zodValidate(schema),
  });

  return (
    <AuthShell title="Registrieren" subtitle="Für Teammitglieder: mit dem Beitrittscode deiner Wohngruppe">
      <form
        onSubmit={form.onSubmit(async (v) => {
          try {
            await register.mutateAsync({
              data: { name: v.name, email: v.email, password: v.password, beitrittscode: v.beitrittscode, datenschutzZugestimmt: v.datenschutz },
            });
            await reset();
            navigate('/', { replace: true });
          } catch (e) {
            notifyError(e, 'Registrierung fehlgeschlagen');
          }
        })}
      >
        <Stack>
          <Alert variant="light" color="blue">
            Nach der Registrierung muss ein Kassenverwalter dich freischalten, bevor du Belege erfassen kannst.
          </Alert>
          <TextInput label="Beitrittscode" description="Bekommst du von deinem Kassenverwalter" autoCapitalize="characters" {...form.getInputProps('beitrittscode')} />
          <TextInput label="Name" autoComplete="name" {...form.getInputProps('name')} />
          <TextInput label="E-Mail" type="email" autoComplete="username" {...form.getInputProps('email')} />
          <PasswordInput label="Passwort" description="Mindestens 10 Zeichen" autoComplete="new-password" {...form.getInputProps('password')} />
          <Checkbox label="Ich habe den Datenschutzhinweis gelesen und stimme zu." {...form.getInputProps('datenschutz', { type: 'checkbox' })} />
          <PrivacyNotice />
          <Button type="submit" loading={register.isPending}>
            Registrieren
          </Button>
          <Text size="sm">
            Schon ein Konto?{' '}
            <Anchor component={Link} to="/login">
              Anmelden
            </Anchor>
          </Text>
        </Stack>
      </form>
    </AuthShell>
  );
}
