import { create } from "zustand";

import type { TavernRenderDto } from "~/services/tavern";

interface TavernCardEntry {
  statusRenderJs: string | null;
  css: string | null;
}

interface TavernState {
  variablesByConversation: Record<string, Record<string, unknown>>;
  cardsByAssistant: Record<string, TavernCardEntry>;
  loadingAssistantIds: Record<string, boolean>;
  setVariables: (conversationId: string, variables: Record<string, unknown>) => void;
  ensureCardLoaded: (assistantId: string) => Promise<void>;
  cardOf: (assistantId: string) => TavernCardEntry | undefined;
}

export const useTavernStore = create<TavernState>()((set, get) => ({
  variablesByConversation: {},
  cardsByAssistant: {},
  loadingAssistantIds: {},

  setVariables: (conversationId, variables) => {
    set((state) => ({
      variablesByConversation: { ...state.variablesByConversation, [conversationId]: variables },
    }));
  },

  ensureCardLoaded: async (assistantId) => {
    if (get().cardsByAssistant[assistantId] || get().loadingAssistantIds[assistantId]) return;
    set((state) => ({
      loadingAssistantIds: { ...state.loadingAssistantIds, [assistantId]: true },
    }));
    try {
      const { fetchTavernRender } = await import("~/services/tavern");
      const data: TavernRenderDto = await fetchTavernRender(assistantId);
      set((state) => ({
        cardsByAssistant: {
          ...state.cardsByAssistant,
          [assistantId]: {
            statusRenderJs: data.statusRenderJs ?? null,
            css: data.css ?? null,
          },
        },
        loadingAssistantIds: { ...state.loadingAssistantIds, [assistantId]: false },
      }));
    } catch {
      set((state) => ({
        cardsByAssistant: {
          ...state.cardsByAssistant,
          [assistantId]: { statusRenderJs: null, css: null },
        },
        loadingAssistantIds: { ...state.loadingAssistantIds, [assistantId]: false },
      }));
    }
  },

  cardOf: (assistantId) => get().cardsByAssistant[assistantId],
}));
