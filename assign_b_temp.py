# 当前状态（基于之前步骤的结果）
a = 1
b = 1
temp = 1

print(f"赋值前：a={a}, b={b}, temp={temp}")

# 执行赋值 b = temp
b = temp

print(f"赋值后：a={a}, b={b}, temp={temp}")

# 验证
assert b == temp, f"错误：b ({b}) 应等于 temp ({temp})"
print("✅ b = temp 赋值成功！")
