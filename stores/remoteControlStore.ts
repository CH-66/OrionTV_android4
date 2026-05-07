import { create } from 'zustand';
import { remoteControlService } from '@/services/remoteControlService';
import Logger from '@/utils/Logger';

const logger = Logger.withTag('RemoteControlStore');

interface RemoteControlState {
  isServerRunning: boolean;
  serverUrl: string | null;
  error: string | null;
  startServer: () => Promise<void>;
  stopServer: () => void;
  isModalVisible: boolean;
  showModal: (targetPage?: string) => void;
  hideModal: () => void;
  lastMessage: string | null;
  targetPage: string | null;
  setMessage: (message: string, targetPage?: string) => void;
  clearMessage: () => void;
}

export const useRemoteControlStore = create<RemoteControlState>((set, get) => ({
  isServerRunning: false,
  serverUrl: null,
  error: null,
  isModalVisible: false,
  lastMessage: null,
  targetPage: null,

  startServer: async () => {
    if (get().isServerRunning) {
      return;
    }
    remoteControlService.init({
      onMessage: (message: string) => {
        logger.debug('Received message:', message);
        const currentState = get();
        // Use the current targetPage from the store
        set({ lastMessage: message, targetPage: currentState.targetPage });
      },
      onHandshake: () => {
        logger.debug('Handshake successful');
        set({ isModalVisible: false })
      },
    });
    try {
      const url = await remoteControlService.startServer();
      logger.info('Server started, URL:', url);
      set({ isServerRunning: true, serverUrl: url, error: null });
    } catch {
      const errorMessage = '启动失败，请强制退应用后重试。';
      logger.error('Failed to start server:', errorMessage);
      set({ error: errorMessage });
    }
  },

  stopServer: () => {
    if (get().isServerRunning) {
      remoteControlService.stopServer();
      set({ isServerRunning: false, serverUrl: null });
    }
  },

  showModal: (targetPage?: string) => set({ isModalVisible: true, targetPage }),
  hideModal: () => set({ isModalVisible: false, targetPage: null }),

  setMessage: (message: string, targetPage?: string) => {
    set({ lastMessage: `${message}_${Date.now()}`, targetPage });
  },

  clearMessage: () => {
    set({ lastMessage: null, targetPage: null });
  },
}));