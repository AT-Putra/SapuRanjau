import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Panel disajikan Spring Boot dari `classpath:/static/admin` (ADR-0013) → `base` harus cocok,
// kalau tidak bundel meminta `/assets/...` yang di prod adalah rute pemain, bukan aset panel.
export default defineConfig({
  base: '/admin/',
  plugins: [react()],
  server: {
    // `vite dev` memanggil server Boot lewat proxy → browser tetap melihat SATU origin, persis
    // seperti di produksi. Tanpa proxy kita harus membuka CORS + `SameSite=None` khusus dev, dan
    // cookie sesi yang dilonggarkan untuk dev adalah hal yang paling gampang ikut terbawa ke prod.
    proxy: { '/admin/api': 'http://localhost:8080' },
  },
});
