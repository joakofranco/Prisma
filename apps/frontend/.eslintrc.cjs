module.exports = {
  root: true,
  env: { browser: true, es2022: true, node: true },
  extends: [
    'plugin:vue/vue3-recommended',
    '@vue/eslint-config-typescript',
    'eslint:recommended',
  ],
  parserOptions: { ecmaVersion: 'latest' },
  rules: {
    'vue/multi-word-component-names': 'off',
  },
};
