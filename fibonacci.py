def fibonacci(n):
    """
    使用两个变量 a, b 迭代计算斐波那契数列的第 n 项。
    
    参数:
        n (int): 非负整数，表示数列的项数（从 0 开始）
        
    返回:
        int: 第 n 项斐波那契数
        
    抛出:
        TypeError: 如果 n 不是整数类型
        ValueError: 如果 n 为负数或超过最大支持范围
        
    示例:
        >>> fibonacci(0)
        0
        >>> fibonacci(1)
        1
        >>> fibonacci(10)
        55
    """
    # ----- 类型检查 -----
    if not isinstance(n, int):
        raise TypeError(
            f"n 必须为整数类型，收到 {type(n).__name__} 类型"
        )
    
    # Python 的 bool 是 int 的子类，单独处理
    if isinstance(n, bool):
        raise TypeError(
            f"n 必须为整数类型，收到 bool 类型 (True/False)"
        )
    
    # ----- 边界检查 -----
    if n < 0:
        raise ValueError(
            f"n 必须为非负整数，收到 n={n}"
        )
    
    # 可选：设置一个合理上限，防止过度计算
    MAX_N = 10_000_000
    if n > MAX_N:
        raise ValueError(
            f"n 超过最大支持范围 {MAX_N:,}，收到 n={n}"
        )
    
    a, b = 0, 1  # F(0) = 0, F(1) = 1
    
    for _ in range(n):
        a, b = b, a + b
    
    return a


if __name__ == "__main__":
    # 简单测试
    for i in range(11):
        print(f"fibonacci({i}) = {fibonacci(i)}")