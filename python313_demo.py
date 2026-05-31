#!/usr/bin/env python3
"""
Python 3.13 新特性综合演示代码
=================================
基于官方 "What's New In Python 3.13" 文档编写
运行环境: Python 3.13+
"""

import sys

# ──────────────────────────────────────────────
# 确保在 Python 3.13+ 上运行
# ──────────────────────────────────────────────
if sys.version_info < (3, 13):
    raise RuntimeError(f"需要 Python 3.13+, 当前版本: {sys.version_info}")

print(f"✅ Python 版本: {sys.version.split()[0]}")
print(f"✅ Python 3.13 发布: 2024年10月7日")
print("=" * 72)


# ╔══════════════════════════════════════════════════════════════╗
# ║  1. 类型参数默认值 (PEP 696)                                ║
# ╚══════════════════════════════════════════════════════════════╝
print("\n" + "─" * 72)
print("📌 1. TypeVar / ParamSpec / TypeVarTuple 默认值 (PEP 696)")
print("─" * 72)

from typing import TypeVar, Generic

T = TypeVar("T", default=int)          # 默认 int
S = TypeVar("S", default=str)          # 默认 str

class Container(Generic[T, S]):
    def __init__(self, value: T, label: S = "default"):
        self.value = value
        self.label = label

c1 = Container(42)                      # T=int, S=str
c2 = Container[int, str](100, "hello")  # 显式指定
print(f"  Container[int, str](100, 'hello') -> value={c2.value}, label={c2.label}")


# ╔══════════════════════════════════════════════════════════════╗
# ║  2. warnings.deprecated() 装饰器 (PEP 702)                  ║
# ╚══════════════════════════════════════════════════════════════╝
print("\n" + "─" * 72)
print("📌 2. warnings.deprecated() 装饰器 (PEP 702)")
print("─" * 72)

import warnings

@warnings.deprecated("请使用 new_function() 替代")
def old_function():
    return "旧函数被调用"

with warnings.catch_warnings(record=True) as w:
    warnings.simplefilter("always")
    result = old_function()
    print(f"  old_function() -> {result}")
    if w:
        print(f"  ⚠️ DeprecationWarning: {w[0].message}")


# ╔══════════════════════════════════════════════════════════════╗
# ║  3. typing.ReadOnly (PEP 705)                               ║
# ╚══════════════════════════════════════════════════════════════╝
print("\n" + "─" * 72)
print("📌 3. typing.ReadOnly — TypedDict 只读字段 (PEP 705)")
print("─" * 72)

from typing import TypedDict, ReadOnly

class User(TypedDict):
    user_id: ReadOnly[int]   # 类型检查器禁止修改
    name: str
    email: str

u: User = {"user_id": 1, "name": "Alice", "email": "alice@example.com"}
print(f"  User = {u}")


# ╔══════════════════════════════════════════════════════════════╗
# ║  4. typing.TypeIs (PEP 742)                                 ║
# ╚══════════════════════════════════════════════════════════════╝
print("\n" + "─" * 72)
print("📌 4. typing.TypeIs — 更直观的类型收窄 (PEP 742)")
print("─" * 72)

from typing import TypeIs

def is_string(val: object) -> TypeIs[str]:
    return isinstance(val, str)

def process(val: object) -> str:
    if is_string(val):
        return val.upper()    # 此处 val 被收窄为 str
    return str(val)

print(f"  process('hello') -> {process('hello')}")
print(f"  process(42)      -> {process(42)}")


# ╔══════════════════════════════════════════════════════════════╗
# ║  5. copy.replace() 与 __replace__ 协议                       ║
# ╚══════════════════════════════════════════════════════════════╝
print("\n" + "─" * 72)
print("📌 5. copy.replace() 与 __replace__ 协议")
print("─" * 72)

import copy
from dataclasses import dataclass
from collections import namedtuple

@dataclass(frozen=True)
class Point:
    x: float
    y: float
    z: float = 0.0

p = Point(1.0, 2.0, 3.0)
p2 = copy.replace(p, z=10.0)
print(f"  copy.replace(dataclass):  Point(1,2,3) -> z=10 => {p2}")

Color = namedtuple("Color", ["r", "g", "b", "alpha"])
red = Color(255, 0, 0, 255)
semi_red = copy.replace(red, alpha=128)
print(f"  copy.replace(namedtuple): Color(255,0,0,255) -> alpha=128 => {semi_red}")


# ╔══════════════════════════════════════════════════════════════╗
# ║  6. math.fma() — 融合乘加运算                                ║
# ╚══════════════════════════════════════════════════════════════╝
print("\n" + "─" * 72)
print("📌 6. math.fma() — 融合乘加 (Fused Multiply-Add)")
print("─" * 72)

import math

result = math.fma(1.0, 2.0, 3.0)  # = 1*2 + 3 = 5.0
print(f"  math.fma(1.0, 2.0, 3.0) = {result}")

x, y, z = 1e100, 1e100, -1e200
standard = (x * y) + z
fused = math.fma(x, y, z)
print(f"  普通计算: (1e100*1e100)+(-1e200) = {standard}")
print(f"  fma:      fma(1e100,1e100,-1e200) = {fused}")


# ╔══════════════════════════════════════════════════════════════╗
# ║  7. glob.translate() — Glob 模式转正则表达式                 ║
# ╚══════════════════════════════════════════════════════════════╝
print("\n" + "─" * 72)
print("📌 7. glob.translate() — Glob 通配符 → 正则表达式")
print("─" * 72)

import glob
import re

pattern = glob.translate("src/**/*.py")
print(f"  glob.translate('src/**/*.py') ->")
print(f"    {pattern[:90]}...")

regex = re.compile(glob.translate("data_*.csv"))
for name in ["data_2024.csv", "data_2025.csv", "image.png"]:
    match = "✅" if regex.fullmatch(name) else "❌"
    print(f"  {match} '{name}'")

# ╔══════════════════════════════════════════════════════════════╗
# ║  8. itertools.batched() 严格模式                             ║
# ╚══════════════════════════════════════════════════════════════╝
print("\n" + "─" * 72)
print("📌 8. itertools.batched() 新增 strict 参数")
print("─" * 72)

import itertools

data = [1, 2, 3, 4, 5, 6, 7]
print(f"  batched(data, 3)                -> {list(itertools.batched(data, 3))}")

try:
    list(itertools.batched(data, 3, strict=True))
except ValueError as e:
    print(f"  batched(data, 3, strict=True)   -> ValueError: {e}")

data2 = [1, 2, 3, 4, 5, 6]
print(f"  batched([1..6], 3, strict=True) -> {list(itertools.batched(data2, 3, strict=True))}")


# ╔══════════════════════════════════════════════════════════════╗
# ║  9. base64.z85encode() / z85decode()                        ║
# ╚══════════════════════════════════════════════════════════════╝
print("\n" + "─" * 72)
print("📌 9. base64 Z85 编码/解码")
print("─" * 72)

import base64

data_bytes = b"Hello, Python 3.13!"
encoded = base64.z85encode(data_bytes)
decoded = base64.z85decode(encoded)
print(f"  原始: {data_bytes}")
print(f"  Z85:  {encoded}")
print(f"  解码: {decoded}")
print(f"  匹配: {data_bytes == decoded}")


# ╔══════════════════════════════════════════════════════════════╗
# ║ 10. statistics.kde() 与 kde_random()                        ║
# ╚══════════════════════════════════════════════════════════════╝
print("\n" + "─" * 72)
print("📌 10. statistics.kde() — 核密度估计")
print("─" * 72)

import statistics

samples = [1.0, 1.5, 2.0, 2.5, 3.0, 3.5, 4.0, 5.0, 6.0, 8.0]
estimation = statistics.kde(samples, h=1.0)
for x in [0, 2, 4, 6, 8, 10]:
    print(f"  kde({x:>2}) = {estimation(x):.4f}")

random_samples = statistics.kde_random(samples, h=1.0, seed=42)
print(f"  采样: {[round(random_samples(), 2) for _ in range(5)]}")


# ╔══════════════════════════════════════════════════════════════╗
# ║ 11. 类的 __static_attributes__ 和 __firstlineno__            ║
# ╚══════════════════════════════════════════════════════════════╝
print("\n" + "─" * 72)
print("📌 11. 类的 __static_attributes__ 和 __firstlineno__")
print("─" * 72)


class MyService:
    """示例服务类"""

    def __init__(self):
        self.name = "service"
        self.version = "1.0"

    def start(self):
        self.status = "running"
        return self.status


print(f"  __static_attributes__: {MyService.__static_attributes__}")
print(f"  __firstlineno__:       {MyService.__firstlineno__}")


# ╔══════════════════════════════════════════════════════════════╗
# ║ 12. 改进的错误提示 — 关键字参数建议                          ║
# ╚══════════════════════════════════════════════════════════════╝
print("\n" + "─" * 72)
print("📌 12. 改进的错误消息 — 关键字参数建议")
print("─" * 72)

print('  "hello".split(max_split=1)')
print("  # Python 3.13 会提示: Did you mean 'maxsplit'?")


# ╔══════════════════════════════════════════════════════════════╗
# ║ 13. str.replace() 支持关键字参数 count                       ║
# ╚══════════════════════════════════════════════════════════════╝
print("\n" + "─" * 72)
print("📌 13. str.replace() count 可作为关键字参数")
print("─" * 72)

text = "apple, banana, apple, cherry, apple"
result = text.replace("apple", "orange", count=2)
print(f'  "{text}"')
print(f'  .replace("apple", "orange", count=2)')
print(f'  -> "{result}"')


# ╔══════════════════════════════════════════════════════════════╗
# ║ 14. exec()/eval() 支持关键字 globals/locals                  ║
# ╚══════════════════════════════════════════════════════════════╝
print("\n" + "─" * 72)
print("📌 14. exec()/eval() 接受关键字参数 globals/locals")
print("─" * 72)

namespace = {"x": 10, "y": 20}
exec("z = x + y", globals=namespace)
print(f"  exec('z = x + y', globals=namespace) -> z = {namespace['z']}")

result = eval("x * y", globals=namespace)
print(f"  eval('x * y', globals=namespace)     -> {result}")


# ╔══════════════════════════════════════════════════════════════╗
# ║ 15. property 对象有了 __name__ 属性                          ║
# ╚══════════════════════════════════════════════════════════════╝
print("\n" + "─" * 72)
print("📌 15. property 对象新增 __name__ 属性")
print("─" * 72)


class Circle:
    def __init__(self, radius):
        self._radius = radius

    @property
    def diameter(self):
        return self._radius * 2

    @property
    def area(self):
        return 3.14159 * self._radius ** 2


print(f"  Circle.diameter.__name__ = {Circle.diameter.__name__}")
print(f"  Circle.area.__name__     = {Circle.area.__name__}")


# ╔══════════════════════════════════════════════════════════════╗
# ║ 16. types.SimpleNamespace 支持位置参数                       ║
# ╚══════════════════════════════════════════════════════════════╝
print("\n" + "─" * 72)
print("📌 16. SimpleNamespace 支持位置参数（映射或键值对）")
print("─" * 72)

from types import SimpleNamespace

ns1 = SimpleNamespace({"x": 1, "y": 2})
print(f"  SimpleNamespace(dict)      -> x={ns1.x}, y={ns1.y}")

ns2 = SimpleNamespace([("a", 10), ("b", 20)])
print(f"  SimpleNamespace(iterable)  -> a={ns2.a}, b={ns2.b}")


# ╔══════════════════════════════════════════════════════════════╗
# ║ 17. pathlib.PurePath.full_match()                           ║
# ╚══════════════════════════════════════════════════════════════╝
print("\n" + "─" * 72)
print("📌 17. pathlib PurePath.full_match() — 递归通配符匹配")
print("─" * 72)

from pathlib import PurePosixPath

path = PurePosixPath("src/python/3.13/demo.py")
print(f"  PurePath('src/python/3.13/demo.py')")
print(f"    full_match('src/**/*.py'): {path.full_match('src/**/*.py')}")
print(f"    full_match('**/demo.py'):  {path.full_match('**/demo.py')}")


# ╔══════════════════════════════════════════════════════════════╗
# ║ 18. pathlib.Path.from_uri()                                 ║
# ╚══════════════════════════════════════════════════════════════╝
print("\n" + "─" * 72)
print("📌 18. Path.from_uri() — 从 file:// URI 创建 Path")
print("─" * 72)

from pathlib import Path

uri_path = Path.from_uri("file:///home/user/data/file.txt")
print(f"  Path.from_uri('file:///home/user/data/file.txt')")
print(f"    -> {uri_path}")
print(f"    parent: {uri_path.parent}")
print(f"    name:   {uri_path.name}")


# ╔══════════════════════════════════════════════════════════════╗
# ║ 19. os.process_cpu_count()                                  ║
# ╚══════════════════════════════════════════════════════════════╝
print("\n" + "─" * 72)
print("📌 19. os.process_cpu_count() 与 PYTHON_CPU_COUNT")
print("─" * 72)

import os

cpu_count = os.process_cpu_count()
print(f"  os.process_cpu_count() = {cpu_count}")
print(f"  os.cpu_count()         = {os.cpu_count()}")


# ╔══════════════════════════════════════════════════════════════╗
# ║ 20. PythonFinalizationError                                 ║
# ╚══════════════════════════════════════════════════════════════╝
print("\n" + "─" * 72)
print("📌 20. PythonFinalizationError")
print("─" * 72)

# PythonFinalizationError 是 Python 3.13 新增的内置异常（builtins 中）
# 当解释器正在终结时调用某些函数（如 thread.start_new_thread, os.fork 等）会抛出此异常
PythonFinalizationError = __builtins__.PythonFinalizationError if hasattr(__builtins__, 'PythonFinalizationError') else None  # type: ignore
print(f"  PythonFinalizationError 是 Python 3.13 新增异常 (继承 RuntimeError)")
print(f"  触发场景: 解释器终结期间调用 os.fork(), subprocess.Popen 等")


# ╔══════════════════════════════════════════════════════════════╗
# ║ 21. argparse 支持废弃参数                                    ║
# ╚══════════════════════════════════════════════════════════════╝
print("\n" + "─" * 72)
print("📌 21. argparse 新增 deprecated 参数")
print("─" * 72)

print("  parser.add_argument('--old-option',")
print("      deprecated='请使用 --new-option 替代')")
print("  # 使用 --old-option 时自动显示 DeprecationWarning")


# ╔══════════════════════════════════════════════════════════════╗
# ║ 22. ast.PyCF_OPTIMIZED_AST                                  ║
# ╚══════════════════════════════════════════════════════════════╝
print("\n" + "─" * 72)
print("📌 22. ast.PyCF_OPTIMIZED_AST — 获取优化后的 AST")
print("─" * 72)

import ast

code = "x = 1 + 2 * 3"

tree = ast.parse(code, mode="exec", optimize=2,
                 flags=ast.PyCF_OPTIMIZED_AST)
print(f"  优化后 AST (常量折叠):")
print(f"  {ast.dump(tree, indent=2)[:400]}...")


# ╔══════════════════════════════════════════════════════════════╗
# ║ 23. except 中允许 global 声明（else 块引用时）               ║
# ╚══════════════════════════════════════════════════════════════╝
print("\n" + "─" * 72)
print("📌 23. except 中允许 global 声明（else 引用时）")
print("─" * 72)

_global_result = None

def divide_safe(a, b):
    """演示 except 块中允许 global 声明（当 else 块中使用时）"""
    try:
        result = a / b
    except ZeroDivisionError:
        global _global_result   # Python 3.13: 以前会 SyntaxError
        _global_result = "被零除"
    else:
        _global_result = result
    return _global_result

print(f"  divide_safe(10, 2) -> {divide_safe(10, 2)}")
print(f"  divide_safe(10, 0) -> {divide_safe(10, 0)}")


# ╔══════════════════════════════════════════════════════════════╗
# ║ 24. 类作用域中允许注解作用域包含 lambda 和推导式             ║
# ╚══════════════════════════════════════════════════════════════╝
print("\n" + "─" * 72)
print("📌 24. 类作用域注解可含 lambda 和推导式")
print("─" * 72)

T = TypeVar("T")

class Registry[T]:
    type Alias = lambda: T          # 类作用域中的 lambda
    names: list[str] = [x.upper() for x in ["a", "b", "c"]]

print(f"  Registry[int].names = {Registry[int].names}")


# ╔══════════════════════════════════════════════════════════════╗
# ║ 25. sys._is_interned() — 检查字符串是否被暂存               ║
# ╚══════════════════════════════════════════════════════════════╝
print("\n" + "─" * 72)
print("📌 25. sys._is_interned() — 检查字符串是否被 interned")
print("─" * 72)

try:
    a = "hello_python_3_13"
    b = sys.intern(a)
    print(f"  sys._is_interned(interned)     = {sys._is_interned(a)}")
    print(f"  sys._is_interned(dynamic str)  = {sys._is_interned('not_interned' + '_test')}")
except AttributeError:
    print("  ⚠️ 当前版本不支持 sys._is_interned()")


# ╔══════════════════════════════════════════════════════════════╗
# ║ 26. PYTHON_HISTORY 环境变量                                  ║
# ╚══════════════════════════════════════════════════════════════╝
print("\n" + "─" * 72)
print("📌 26. PYTHON_HISTORY — 自定义历史文件路径")
print("─" * 72)

print("  export PYTHON_HISTORY=$HOME/.config/python/history")
print("  # 然后启动 Python REPL 即可生效")


# ╔══════════════════════════════════════════════════════════════╗
# ║ 27. 检查 GIL 状态 (Free-threaded CPython)                    ║
# ╚══════════════════════════════════════════════════════════════╝
print("\n" + "─" * 72)
print("📌 27. 运行时检查 GIL 状态 (PEP 703)")
print("─" * 72)

try:
    gil_enabled = sys._is_gil_enabled()
    print(f"  GIL 启用状态: {gil_enabled}")
except AttributeError:
    print("  ⚠️ 此版本不支持 sys._is_gil_enabled()")


# ╔══════════════════════════════════════════════════════════════╗
# ║ 总结                                                        ║
# ╚══════════════════════════════════════════════════════════════╝
print("\n" + "=" * 72)
print("🎉 Python 3.13 新特性演示完成!")
print("=" * 72)
print(f"""
📋 本演示涵盖的主要特性:

类型系统:
  ✅ TypeVar/ParamSpec/TypeVarTuple 默认值 (PEP 696)
  ✅ warnings.deprecated() 装饰器 (PEP 702)
  ✅ ReadOnly — TypedDict 只读字段 (PEP 705)
  ✅ TypeIs — 更直观的类型收窄 (PEP 742)

标准库增强:
  ✅ copy.replace() + __replace__ 协议
  ✅ math.fma() — 融合乘加
  ✅ glob.translate() — Glob → 正则
  ✅ itertools.batched(strict=True)
  ✅ base64.z85encode / z85decode
  ✅ statistics.kde() + kde_random()
  ✅ argparse 废弃参数支持

语言改进:
  ✅ str.replace(count=) 关键字参数
  ✅ exec/eval 关键字 globals/locals
  ✅ property.__name__ 属性
  ✅ except 中允许 global 声明
  ✅ 类注解区域支持 lambda/推导式

类 & 调试:
  ✅ __static_attributes__, __firstlineno__
  ✅ PYTHON_HISTORY 环境变量
  ✅ PythonFinalizationError
  ✅ sys._is_interned() 字符串暂存检查

平台 & 性能:
  ✅ Free-threaded CPython (PEP 703)
  ✅ 实验性 JIT 编译器 (PEP 744)
  ✅ os.process_cpu_count()
  ✅ Android/iOS 官方支持 (PEP 730/738)
""")