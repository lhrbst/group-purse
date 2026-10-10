import type { ReactNode } from 'react';
import { Modal } from '@mantine/core';

export function ActionModal({ opened, onClose, title, children }: { opened: boolean; onClose: () => void; title: string; children: ReactNode }) {
  return (
    <Modal opened={opened} onClose={onClose} title={title} centered>
      {children}
    </Modal>
  );
}
