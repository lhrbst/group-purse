import { Alert, Center, Loader } from '@mantine/core';
import { errorMessage } from '../lib/errors';

export function Loading() {
  return (
    <Center py="xl">
      <Loader />
    </Center>
  );
}

export function LoadError({ error }: { error: unknown }) {
  return (
    <Alert color="red" title="Das hat nicht geklappt">
      {errorMessage(error)}
    </Alert>
  );
}
