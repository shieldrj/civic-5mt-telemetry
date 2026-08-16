import type { CapacitorConfig } from '@capacitor/cli';

const config: CapacitorConfig = {
  appId: 'com.shieldrj.civic5mt',
  appName: 'Civic 5MT',
  webDir: 'dist',
  android: {
    // The gauges are already dark; this stops a white flash on launch.
    backgroundColor: '#08090d',
  },
};

export default config;
