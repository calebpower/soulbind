/*
 * Flarum's standard extension build. Nothing project-specific: the only
 * JavaScript here is one settings page, and a bespoke build would be more code
 * than it compiles.
 */
const config = require('flarum-webpack-config');

module.exports = config();
