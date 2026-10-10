// @author kongweiguang
export interface SseFrame {
  event: string;
  data: string[];
  id?: string;
}
