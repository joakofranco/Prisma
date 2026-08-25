import { defineStore } from 'pinia';
import { ref } from 'vue';

export type NotificationType = 'success' | 'error' | 'warning' | 'info';

export interface NotificationItem {
  id: number;
  message: string;
  type: NotificationType;
}

export const useNotificationStore = defineStore('notification', () => {
  const notifications = ref<NotificationItem[]>([]);
  let nextId = 0;

  function notify(message: string, type: NotificationType = 'info', timeout = 5000) {
    const id = nextId++;
    notifications.value.push({ id, message, type });
    if (timeout > 0) {
      setTimeout(() => dismiss(id), timeout);
    }
    return id;
  }

  function dismiss(id: number) {
    notifications.value = notifications.value.filter((n) => n.id !== id);
  }

  function success(message: string) {
    return notify(message, 'success');
  }
  function error(message: string) {
    return notify(message, 'error', 7000);
  }
  function warning(message: string) {
    return notify(message, 'warning');
  }
  function info(message: string) {
    return notify(message, 'info');
  }
  function clear() {
    notifications.value = [];
  }

  return { notifications, notify, dismiss, success, error, warning, info, clear };
});
