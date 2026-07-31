import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { HashRouter } from 'react-router-dom';
import { App } from './App';

// HashRouter, bukan BrowserRouter bawaan react-admin: dengan hash, `/admin/#/admin-users` tetap
// merupakan permintaan untuk `/admin/index.html` — satu-satunya berkas yang memang ada sebagai
// static resource. BrowserRouter menuntut server memantulkan setiap deep-link ke index.html, dan
// itu berarti menulis rute pantulan di Spring untuk masalah yang bisa tidak ada.
createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <HashRouter>
      <App />
    </HashRouter>
  </StrictMode>,
);
