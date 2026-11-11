import 'i18next';
import type en from './en.json';

// Types the keys of t() from the English file: t('menu.hom') or a missing {{org}} is a compile error,
// where Spring only finds an unknown message key at runtime.
declare module 'i18next' {
  interface CustomTypeOptions {
    defaultNS: 'translation';
    resources: {
      translation: typeof en;
    };
  }
}
