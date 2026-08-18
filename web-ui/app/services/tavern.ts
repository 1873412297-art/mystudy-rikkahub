import api from "./api";

export interface TavernRenderDto {
  statusRenderJs?: string | null;
  css?: string | null;
}

export function fetchTavernRender(assistantId: string): Promise<TavernRenderDto> {
  return api.get<TavernRenderDto>(`assistant/${assistantId}/tavern-render`);
}
