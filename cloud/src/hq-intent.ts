/**
 * 「用户要看细节」的措辞判定。
 *
 * 为什么不交给模型选工具：实测 deepseek-flash 在同一句话上三次里有两次挑了低清
 * `take_photo`（工具描述里已经写明「要看清细节就用 take_photo_hq」也没用）。
 * 所以「要不要高清」由**用户说的话**决定 —— 命中这里就先拍高清再把图交给模型。
 *
 * 误判的代价是白花 ~8 s；漏判的代价是用户拿到一张糊图、还得再说一遍。所以宁可宽一点。
 */
export const HQ_INTENT =
  /高清|清晰|仔细|看清|看清楚|放大|凑近|什么字|写的什么|读一下|念一下|型号|标签|说明书|小字/;

/** 判定一句话是不是在要求看清细节。 */
export function wantsHighRes(text: string): boolean {
  return HQ_INTENT.test(text);
}
