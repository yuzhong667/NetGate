package com.d.netgate;

/**
 * Shizuku UserService 的接口契约。
 *
 * 16777114 是 Shizuku 内部保留的 destroy transaction id，必须一字不差。
 * 自定义方法从 1 开始编号，绝不能占用这个 id。
 */
interface IUserService {
    void destroy() = 16777114;
    String exec(String command) = 1;
}
