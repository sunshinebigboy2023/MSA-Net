export type AnalysisFormValues = {
  text?: string;
  language?: 'auto' | 'zh' | 'en';
  enhanceTextWithTranscript?: boolean;
  video?: File | Blob;
  browserLanguage?: string;
};

const countChineseChars = (text: string) => (text.match(/[\u4e00-\u9fff]/g) || []).length;

const countLatinChars = (text: string) => (text.match(/[a-z]/gi) || []).length;

export const resolveAnalysisLanguage = (
  language: AnalysisFormValues['language'],
  text = '',
  browserLanguage = '',
) => {
  if (language && language !== 'auto') {
    return language;
  }

  const chineseChars = countChineseChars(text);
  const latinChars = countLatinChars(text);
  if (chineseChars >= 2 && chineseChars >= Math.ceil(latinChars * 0.35)) {
    return 'zh';
  }
  if (latinChars >= 3 && chineseChars === 0) {
    return 'en';
  }
  if (browserLanguage.toLowerCase().startsWith('zh')) {
    return 'zh';
  }
  return undefined;
};

export const buildAnalysisFormData = (values: AnalysisFormValues) => {
  const formData = new FormData();
  const text = (values.text || '').trim();
  const hasVideo = Boolean(values.video);
  const resolvedLanguage = resolveAnalysisLanguage(values.language, text, values.browserLanguage);

  if (text) {
    formData.append('text', text);
  }

  if (resolvedLanguage) {
    formData.append('language', resolvedLanguage);
  }

  if (text && hasVideo && values.enhanceTextWithTranscript) {
    formData.append('enhanceTextWithTranscript', 'true');
  }

  if (values.video) {
    formData.append('video', values.video);
  }

  return formData;
};
