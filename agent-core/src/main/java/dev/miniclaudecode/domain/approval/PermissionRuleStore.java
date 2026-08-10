package dev.miniclaudecode.domain.approval;

import java.util.List;

public interface PermissionRuleStore {
  PermissionRuleStore NONE =
      new PermissionRuleStore() {
        @Override
        public List<PermissionRule> list() {
          return List.of();
        }

        @Override
        public void save(PermissionRule rule) {}
      };

  List<PermissionRule> list();

  void save(PermissionRule rule);
}
