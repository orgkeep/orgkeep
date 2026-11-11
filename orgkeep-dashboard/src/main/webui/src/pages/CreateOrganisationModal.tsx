import { Alert, Button, Fieldset, Group, Modal, SimpleGrid, Stack, TagsInput, Text, TextInput } from '@mantine/core';
import { useForm } from '@mantine/form';
import { useTranslation } from 'react-i18next';
import { type CreateOrgRequest, useCreateOrganisation } from '../api/organisations';
import { isSlug } from './slug';

// Loose on purpose: the core does the real check (@Email)
const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+$/;

interface CreateOrganisationModalProps {
  opened: boolean;
  onClose: () => void;
}

// The values of the form: plain strings, '' for an empty field. Turned into a CreateOrgRequest on submit.
interface FormValues {
  name: string;
  emailDomains: string[];
  ownerEmail: string;
  ownerFirstName: string;
  ownerLastName: string;
}

// '' -> null: the core treats a missing owner as "no owner", not as an owner with an empty email
function emptyToNull(value: string): string | null {
  return value.trim() === '' ? null : value.trim();
}

// An owner is created only with an email: the core then requires the first and last name (Keycloak asks for them
// at login otherwise)
function hasOwner(values: FormValues): boolean {
  return values.ownerEmail.trim() !== '';
}

function toRequest(values: FormValues): CreateOrgRequest {
  return {
    name: values.name.trim(),
    emailDomains: values.emailDomains,
    ownerEmail: emptyToNull(values.ownerEmail),
    ownerFirstName: emptyToNull(values.ownerFirstName),
    ownerLastName: emptyToNull(values.ownerLastName),
  };
}

export default function CreateOrganisationModal({ opened, onClose }: CreateOrganisationModalProps) {
  const { t } = useTranslation();
  const createOrg = useCreateOrganisation();

  // useForm holds the values, the validation rules and the errors of the form,
  // a bit like a form-backing bean with Bean Validation annotations in Spring MVC
  const form = useForm<FormValues>({
    initialValues: { name: '', emailDomains: [], ownerEmail: '', ownerFirstName: '', ownerLastName: '' },
    // each rule returns null when the value is valid, else the error message.
    // The second argument is all the values of the form: the names are required only with an owner email
    validate: {
      // same rule as CreateOrgRequest.name in orgkeep-core
      name: (value) => (isSlug(value) ? null : t('organisations.create.nameInvalid')),
      ownerEmail: (value) =>
        value.trim() === '' || EMAIL_PATTERN.test(value.trim()) ? null : t('organisations.create.emailInvalid'),
      ownerFirstName: (value, values) => (hasOwner(values) && value.trim() === '' ? t('common.required') : null),
      ownerLastName: (value, values) => (hasOwner(values) && value.trim() === '' ? t('common.required') : null),
    },
  });

  function close() {
    // the next opening starts from an empty form, without the error of the previous attempt
    form.reset();
    createOrg.reset();
    onClose();
  }

  // form.onSubmit runs the validation first, and calls this function only if every field is valid
  const submit = form.onSubmit((values) => {
    createOrg.mutate(toRequest(values), { onSuccess: close });
  });

  return (
    <Modal opened={opened} onClose={close} title={t('organisations.create.title')} size="lg">
      <form onSubmit={submit}>
        <Stack>
          <TextInput
            label={t('organisations.create.name')}
            description={t('organisations.create.nameDescription')}
            placeholder="globex"
            withAsterisk
            data-autofocus
            styles={{ input: { fontFamily: 'monospace' } }}
            // getInputProps wires value, onChange and error of the field in one go
            {...form.getInputProps('name')}
          />

          <TagsInput
            label={t('organisations.create.emailDomains')}
            description={t('organisations.create.emailDomainsDescription')}
            placeholder="globex.com"
            // a comma or a space also ends a domain, not only Enter
            splitChars={[',', ' ']}
            {...form.getInputProps('emailDomains')}
          />

          <Fieldset legend={t('organisations.create.owner')}>
            <Stack gap="sm">
              <TextInput
                label={t('organisations.create.ownerEmail')}
                placeholder="carol@globex.com"
                type="email"
                {...form.getInputProps('ownerEmail')}
              />
              <SimpleGrid cols={{ base: 1, xs: 2 }}>
                {/* the asterisk follows the email: the names are required only with an owner */}
                <TextInput
                  label={t('organisations.create.ownerFirstName')}
                  withAsterisk={hasOwner(form.values)}
                  {...form.getInputProps('ownerFirstName')}
                />
                <TextInput
                  label={t('organisations.create.ownerLastName')}
                  withAsterisk={hasOwner(form.values)}
                  {...form.getInputProps('ownerLastName')}
                />
              </SimpleGrid>
              <Text size="xs" c="dimmed">
                {t('organisations.create.ownerDescription')}
              </Text>
            </Stack>
          </Fieldset>

          {/* the error of the backend, e.g. 409 "already exists": the modal stays open to fix the values */}
          {createOrg.error && (
            <Alert color="red" title={t('organisations.create.failed')}>
              {createOrg.error.message}
            </Alert>
          )}

          <Group justify="flex-end">
            <Button variant="default" onClick={close}>
              {t('common.cancel')}
            </Button>
            <Button type="submit" loading={createOrg.isPending}>
              {t('organisations.create.submit')}
            </Button>
          </Group>
        </Stack>
      </form>
    </Modal>
  );
}
