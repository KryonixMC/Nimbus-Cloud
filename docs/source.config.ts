import { defineDocs, defineConfig } from 'fumadocs-mdx/config';
import { rehypeNimbus } from './lib/rehype-nimbus';

export const docs = defineDocs({
  dir: 'content/docs',
});

export default defineConfig({
  mdxOptions: {
    rehypePlugins: (v) => [...v, rehypeNimbus],
  },
});
