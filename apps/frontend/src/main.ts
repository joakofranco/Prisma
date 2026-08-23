import { createApp } from 'vue';
import { createPinia } from 'pinia';
import App from './App.vue';
import router from './router';
import { useAuthStore } from './stores/auth';
import { useThemeStore } from './stores/theme';
import './assets/styles/main.css';
import './plugins/chartjs';
import { initLogRocket } from './plugins/logrocket';

initLogRocket();

const app = createApp(App);
const pinia = createPinia();

app.use(pinia);

// Antes de montar: aplica la clase "dark" a <html> (o no) desde el arranque, para no pintar
// primero en claro y recién corregir a oscuro una vez montado el primer componente que lee el
// store (flash de tema incorrecto).
useThemeStore().init();

// El router debe instalarse (y disparar su navegación inicial) recién
// después de que Keycloak haya terminado de inicializarse. Si se instala
// antes, el guard de navegación corre con `isAuthenticated=false` por
// defecto y redirige a /login, lo que reescribe la URL vía History API y
// destruye el fragmento `#state=...&code=...` que Keycloak necesita para
// completar el login al volver del redirect (ver bug: loop /login).
const authStore = useAuthStore();
authStore.init().finally(() => {
  app.use(router);
  app.mount('#app');
});
