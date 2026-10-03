// SPDX-License-Identifier: MIT
int wg_java_floor_div(int left, int right) { int quotient = left / right; int remainder = left - quotient * right; return remainder != 0 && ((remainder ^ right) < 0) ? quotient - 1 : quotient; }
int wg_java_floor_mod(int left, int right) { return left - wg_java_floor_div(left, right) * right; }
