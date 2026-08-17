import type { CapacitorConfig } from '@capacitor/cli';

const config: CapacitorConfig = {
  appId: 'com.shieldrj.civic5mt',
  appName: 'Civic 5MT',
  webDir: 'dist',
  android: {
    // The gauges are already dark; this stops a white flash on launch. Kept in step with
    // --ground in index.css and the theme-color meta - it was left on the old #08090d
    // through the redesign, which turned the flash it exists to prevent into a dark one.
    backgroundColor: '#101215',
  },
};

export default config;
