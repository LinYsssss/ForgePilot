/**
 * vcs 层：git 命令行封装。
 *
 * <p>分层约定（本次拆分后）：
 * <ul>
 *   <li>{@link com.acme.devhub.vcs.VcsCliService} —— git 语义层：镜像同步、
 *       变更清单、diff 导出；对外公共入口，签名零变更。</li>
 *   <li>{@code VcsProcessRunner}（包私有）—— 子进程执行边界：命令组装、
 *       askpass 凭据注入、超时控制、限量抽干、失败输出脱敏。</li>
 * </ul>
 * 凭据密文的解封见 {@link com.acme.devhub.common.SecretBox}。
 */
package com.acme.devhub.vcs;
