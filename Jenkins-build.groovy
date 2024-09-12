pipeline {
    agent any

    options {
        ansiColor('xterm')
    }

    parameters {
        string(name: 'MILU2_INFRA_GIT_BRANCH', defaultValue: 'main', description: 'Git branch storing Terraform.')
    }

    environment {
        AWS_ACCESS_KEY_ID     = credentials('aws-secret-key-id')
        AWS_SECRET_ACCESS_KEY = credentials('aws-secret-access-key')
        GIT_CREDENTIAL = 'github-access'
        INSTALLER_DIR = 'terraform'
        TF_VAR_SSH_PRIVATE_KEY = credentials('SSH_PRIVATE_KEY')
        TF_IN_AUTOMATION = 'true'        
    }

    stages {
        stage('Checkout SCM') {
            steps {
                deleteDir()
                dir(INSTALLER_DIR) {
                    checkout([
                        $class: 'GitSCM',
                        branches: [[name: MILU2_INFRA_GIT_BRANCH]],
                        doGenerateSubmoduleConfigurations: false,
                        extensions: [[$class: 'CleanBeforeCheckout']],
                        submoduleCfg: [],
                        userRemoteConfigs: [
                            [
                                credentialsId: GIT_CREDENTIAL,
                                url: 'git@github.com:phanthaihuan/terraform2024.git'
                            ]
                        ]
                    ])
                }
            }
        }

        stage('Init Provider') {
            steps {
                dir(INSTALLER_DIR) {
                    sh 'terraform init'
                }
            }
        }

        stage('Validate configuration files') {
            steps {
                dir(INSTALLER_DIR) {
                    sh 'terraform validate'
                    sh 'if [ $? -ne 0 ]; then exit 1; fi'
                }
            }
        }

        stage('Plan Resources') {
            steps {
                dir(INSTALLER_DIR) {
                    sh 'terraform plan'
                }
            }
        }

        stage('Apply Resources') {
            steps {
                script {
                    dir(INSTALLER_DIR) {
                        sh """
                            terraform apply -auto-approve \
                            -var 'SSH_PRIVATE_KEY=${TF_VAR_SSH_PRIVATE_KEY}'
                        """
                    }
                }
            }
        }
        
        stage(' Wait for EC2 to be ready') {
            steps {
                dir(INSTALLER_DIR) {
                    sh '''
                        echo $(terraform output -json ec2_public_ip) | awk -F'"' '{print $2}' > ansible/ansible_inventory
                        cat ansible/ansible_inventory
                        aws ec2 wait instance-status-ok --region ap-southeast-1 --instance-ids `$(terraform output -json ec2_instance_id) | awk -F'"' '{print $2}'`
                    '''
                }
            }
        }
        
        stage('Run Ansible') {
            environment {
                ANSIBLE_HOST_KEY_CHECKING = 'False'
                ANSIBLE_CONFIG = 'ansible/ansible.cfg'
            }
            steps {
                dir(INSTALLER_DIR) {
                    ansiblePlaybook(
                    inventory: 'ansible/ansible_inventory',
                    playbook: 'ansible/playbook.yaml',
                    credentialsId: 'SSH_PRIVATE_KEY',
                    extras: '-v')
                }
            }
        }        
    }
}
