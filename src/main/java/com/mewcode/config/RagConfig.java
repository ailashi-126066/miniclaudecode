package com.mewcode.config;

public class RagConfig {
    private boolean enabled = true;
    private String indexPath = ".mewcode/rag-index";
    /** off = tools only; auto = relevant requests; always = every non-command request. */
    private String knowledgeMode = "auto";
    private EmbeddingConfig embedding = new EmbeddingConfig();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) { enabled = v; }
    public String getIndexPath() { return indexPath; }
    public void setIndexPath(String v) { indexPath = v; }
    public String getKnowledgeMode() { return knowledgeMode; }
    public void setKnowledgeMode(String value) { knowledgeMode = value; }
    public EmbeddingConfig getEmbedding() { return embedding; }
    public void setEmbedding(EmbeddingConfig v) { embedding = v; }
}
