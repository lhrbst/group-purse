import type { ReactNode } from 'react';
import { Container, Paper, Title, Text } from '@mantine/core';

export function AuthShell({ title, subtitle, children }: { title: string; subtitle?: string; children: ReactNode }) {
  return (
    <Container size={440} py={40}>
      <Title ta="center" order={2}>
        Gruppenkasse
      </Title>
      <Text ta="center" c="dimmed" size="sm" mt={4} mb="lg">
        {subtitle ?? 'Kasse und Belege der Wohngruppe'}
      </Text>
      <Paper withBorder shadow="sm" p="lg" radius="md">
        <Title order={3} mb="md">
          {title}
        </Title>
        {children}
      </Paper>
    </Container>
  );
}
