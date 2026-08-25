import { ref } from 'vue';
import { useNotificationStore } from '@/stores/notification';

export function useConfirm() {
  const show = ref(false);
  const message = ref('');
  const title = ref('');
  let resolvePromise: ((value: boolean) => void) | null = null;

  function confirm(msg: string, ttl: string = 'Confirmar'): Promise<boolean> {
    message.value = msg;
    title.value = ttl;
    show.value = true;
    return new Promise((resolve) => {
      resolvePromise = resolve;
    });
  }

  function handleConfirm() {
    show.value = false;
    resolvePromise?.(true);
    resolvePromise = null;
  }

  function handleCancel() {
    show.value = false;
    resolvePromise?.(false);
    resolvePromise = null;
  }

  return { show, message, title, confirm, handleConfirm, handleCancel };
}

export function useNotification() {
  const store = useNotificationStore();

  return {
    notifications: store.notifications,
    notify: store.notify,
    success: store.success,
    error: store.error,
    warning: store.warning,
    info: store.info,
  };
}

export function usePagination(totalItems: { value: number }, initialPageSize: number = 10) {
  const currentPage = ref(1);
  const pageSize = ref(initialPageSize);
  const totalPages = ref(Math.ceil(totalItems.value / pageSize.value));

  function goToPage(page: number) {
    if (page >= 1 && page <= totalPages.value) {
      currentPage.value = page;
    }
  }

  function nextPage() {
    goToPage(currentPage.value + 1);
  }
  function prevPage() {
    goToPage(currentPage.value - 1);
  }

  function setPageSize(size: number) {
    pageSize.value = size;
    currentPage.value = 1;
  }

  return { currentPage, pageSize, totalPages, goToPage, nextPage, prevPage, setPageSize };
}
