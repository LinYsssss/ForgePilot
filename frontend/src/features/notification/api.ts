import { requestJson } from "../../lib/http";

/**
 * 一个项目的钉钉通知配置。
 *
 * **这里没有凭据。** webhook URL 与加签密钥写进去之后就再也读不出来——URL 里带着
 * `access_token`，回显它等于把发消息的权限交给任何能打开这个页面的人。与 SCM 的
 * token / webhookSecret 是同一套 write-only 语义。
 */
export interface NotificationChannel {
  configured: boolean;
  enabled: boolean;
  /**
   * 这个渠道有没有配加签密钥。不是凭据，是一个必须被看见的事实：不加签时，任何拿到
   * webhook URL 的人都能往群里发消息，界面若不说，配置的人就不知道自己停在哪一档。
   */
  signed: boolean;
  /**
   * 机器人「自定义关键词」安全设置里那个词。不是凭据，所以照常读得回来——
   * 它是「消息为什么没到」最常见的原因，而配错了从消息本身完全看不出来。
   */
  keyword: string | null;
  updatedAt: string | null;
}

function channelPath(projectId: number): string {
  return `/api/projects/${projectId}/notifications/dingtalk`;
}

export function getNotificationChannel(projectId: number): Promise<NotificationChannel> {
  return requestJson<NotificationChannel>(channelPath(projectId));
}

/**
 * 整组重填。没有「只改一个字段」的路径：凭据读不回来，调用方无从知道自己没填的
 * 那一半现在是什么，所以部分更新在这里没有意义。
 *
 * `secret` 留空表示不加签——钉钉的安全设置只在创建机器人时可选，已建好的往往改不了。
 */
export function configureNotificationChannel(
  projectId: number,
  input: { webhookUrl: string; secret: string; keyword: string; enabled: boolean },
): Promise<NotificationChannel> {
  return requestJson<NotificationChannel>(channelPath(projectId), {
    method: "PUT",
    body: JSON.stringify(input),
  });
}

export function removeNotificationChannel(projectId: number): Promise<void> {
  return requestJson<void>(channelPath(projectId), { method: "DELETE" });
}
