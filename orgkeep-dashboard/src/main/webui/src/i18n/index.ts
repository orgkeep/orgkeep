import i18n from 'i18next';
import { initReactI18next } from 'react-i18next';
import en from './en.json';
import fr from './fr.json';

// The texts of the UI, one JSON file per language: the counterpart of Spring's messages.properties /
// messages_fr.properties. English is the reference: every other language must have the same keys.
// Adding a language = a new JSON file with the same keys, and a line in `resources` and `supportedLanguages`.
export const resources = {
  en: { translation: en },
  // "satisfies": a key of en.json missing from fr.json is a compile error, not a silent fallback
  fr: { translation: fr satisfies typeof en },
} as const;

const supportedLanguages = Object.keys(resources);

// The first language of the browser that we support, else English
// (what Spring's AcceptHeaderLocaleResolver does with the Accept-Language header).
function browserLanguage(): string {
  const preferred = navigator.languages.map((tag) => tag.split('-')[0]);
  return preferred.find((lang) => supportedLanguages.includes(lang)) ?? 'en';
}

i18n
  // makes i18n available to the useTranslation() hook in every component
  .use(initReactI18next)
  .init({
    resources,
    lng: browserLanguage(),
    fallbackLng: 'en',
    // the texts are bundled with the app: no need to load them asynchronously
    initAsync: false,
    // React already escapes what it renders: escaping here too would show "&amp;" instead of "&"
    interpolation: { escapeValue: false },
  });

// keeps <html lang="..."> in sync, for screen readers and the browser's spell checker
document.documentElement.lang = i18n.language;
i18n.on('languageChanged', (lang) => {
  document.documentElement.lang = lang;
});

export default i18n;
