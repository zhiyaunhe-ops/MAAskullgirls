// SGM挂机 — Shizuku UserService: 命令在 Shizuku server 进程内执行 (shell uid)。
// 装载方: Shizuku.bindUserService(UserServiceArgs(ComponentName(ShellService)), conn)。
package com.zhiyaunhe.sgmbot.shizuku;

interface IShellService {
    /** 同步执行 sh -c <cmd>, 返回合并的 stdout+stderr (截断到 4MB); 异常返回 "ERR: ..." */
    String exec(String cmd);

    /** 活性探测 (binder 调用通不通, 不做实事) */
    String ping();
}
