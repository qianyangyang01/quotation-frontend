import js from '@eslint/js'
import globals from 'globals'
import tseslint from 'typescript-eslint'
import vue from 'eslint-plugin-vue'

export default [
  { ignores: ['dist/**', 'node_modules/**', 'backend/**', 'outputs/**'] },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  ...vue.configs['flat/essential'],
  {
    files: ['scripts/**/*.{js,mjs}'],
    languageOptions: { globals: { ...globals.node, ...globals.browser, URL: 'readonly' } },
  },
  {
    files: ['src/**/*.{ts,vue}'],
    languageOptions: {
      globals: { ...globals.browser, ...globals.es2022 },
      parserOptions: { parser: tseslint.parser, extraFileExtensions: ['.vue'] },
    },
    rules: {
      '@typescript-eslint/no-explicit-any': 'off',
      '@typescript-eslint/no-unused-vars': ['error', { argsIgnorePattern: '^_' }],
      'no-undef': 'off',
      'vue/no-mutating-props': 'off',
    },
  },
]
